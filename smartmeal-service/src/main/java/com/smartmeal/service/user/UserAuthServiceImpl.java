package com.smartmeal.service.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartmeal.common.cache.CacheService;
import com.smartmeal.common.constant.CommonConstants;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.entity.User;
import com.smartmeal.repository.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 账号认证实现。
 *
 * <p><b>三个安全细节，都不是「顺手加的」，而是各有明确针对的攻击面：</b>
 *
 * <ol>
 *   <li><b>失败原因对外完全一致</b>。用户不存在、密码错、账号被禁用，
 *       三种情况返回同一个 {@link ResultCode#PASSWORD_ERROR}。
 *       如果给「用户不存在」单独一个提示，登录接口就变成了用户名枚举器 ——
 *       攻击者拿一份常见用户名字典跑一遍，就能筛出哪些账号真实存在，
 *       接下来只需要针对这些账号爆破密码。</li>
 *
 *   <li><b>用户不存在时也要跑一次 BCrypt</b>。BCrypt 一次要几十毫秒，
 *       如果「查无此人」直接返回、跳过哈希计算，那么它的响应时间会明显短于
 *       「密码错误」，攻击者用响应时间差同样能枚举出用户名 ——
 *       这就是所谓的时序侧信道。代价是每次不存在的登录都要多算一次哈希，
 *       理论上可以被用来消耗 CPU；实际影响由登录失败计数与上游限流兜住。</li>
 *
 *   <li><b>失败计数成功后必须清零</b>。否则「前 4 次输错、第 5 次输对」
 *       这种正常行为会把计数器留在 4，用户下次只要再错一次就被锁 ——
 *       看起来像系统坏了，其实是计数器语义没想清楚。</li>
 * </ol>
 *
 * <p><b>已知局限（有意保留，写在这里避免以后被当成 bug）</b>：
 * 失败计数只按用户名维度，不按来源 IP。这意味着知道用户名的人可以故意输错密码
 * 把账号锁死（一种定向 DoS）。生产环境应该改成「用户名 + IP」双维度，
 * 或者用指数退避代替硬锁定。之所以没做，是因为本项目的 Service 层拿不到请求 IP
 * （那需要往业务方法里传 Web 层的东西），而为了这个把 IP 一路透传下来
 * 会污染整条调用链 —— 取舍是「先记录清楚，不假装解决了」。
 */
@Slf4j
@Service
public class UserAuthServiceImpl implements UserAuthService {

    /** 连续失败多少次触发锁定。 */
    static final int MAX_FAIL_COUNT = 5;

    /** 锁定与计数窗口时长。 */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * 密码长度上限。
     *
     * <p>BCrypt 只读取前 72 个字节，超出部分被静默忽略。限制到 64 个字符，
     * ASCII 口令不会触到这个边界；中文等非 ASCII 口令有可能超过 72 字节而被截断 ——
     * 这是 BCrypt 的固有行为（不是本项目的 bug），但它会让「密码长度上限」
     * 这件事变得不那么直观，所以在这里说明。
     */
    static final int MAX_PASSWORD_LENGTH = 64;

    static final int MIN_USERNAME_LENGTH = 3;
    static final int MAX_USERNAME_LENGTH = 64;

    /** 用户名规则：字母 / 数字 / 下划线。不收中文和空格，避免与日志、URL、缓存 key 打架。 */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_]{" + MIN_USERNAME_LENGTH + "," + MAX_USERNAME_LENGTH + "}$");

    /**
     * BCrypt 哈希的合法形态：算法标识 {@code $2a$} / {@code $2b$} / {@code $2y$} +
     * 两位轮数 + {@code $} + 53 位 base64 字符（22 位 salt + 31 位摘要），共 60 字符。
     *
     * <p>base64 的字母表用的是 BCrypt 自己的变体：{@code ./A-Za-z0-9}，
     * 所以下划线、短横线这类字符**不在**合法集合里。
     */
    private static final Pattern BCRYPT_HASH_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final CacheService cacheService;

    /**
     * 时序对齐用的假哈希。
     *
     * <p>在构造时算一次，之后所有「用户不存在」的分支都拿它跑一次 {@code matches()}，
     * 让响应时间和「密码错误」处于同一量级。内容本身无意义，随便什么都行。
     */
    private final String timingEqualizerHash;

    public UserAuthServiceImpl(UserMapper userMapper, PasswordEncoder passwordEncoder,
                               CacheService cacheService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.cacheService = cacheService;
        this.timingEqualizerHash = passwordEncoder.encode("smartmeal-timing-equalizer");
    }

    // ==================== 登录 ====================

    @Override
    public User authenticate(String username, String rawPassword) {
        String name = normalizeUsername(username);

        // 已锁定时提前拒绝，连数据库都不该碰 —— 否则锁定只能防住「猜对密码」，
        // 防不住「打满数据库连接池」。
        // 注意这只是**优化**，不是锁定的唯一防线：真正的判定在 failure() 里，
        // 用的是 increment 的返回值。这里读不到计数器（比如换了缓存实现）
        // 只会退化成「多查一次库」，不会让锁定失效。
        long failCount = currentFailCount(name);
        if (failCount >= MAX_FAIL_COUNT) {
            log.warn("账号已锁定，拒绝登录 username={} 累计失败={}", name, failCount);
            throw new BusinessException(ResultCode.AUTH_LOCKED,
                    "登录失败次数过多，请 " + LOCK_DURATION.toMinutes() + " 分钟后再试");
        }

        User user = findByUsername(name);

        if (user == null) {
            // 陪跑一次哈希，抹平与「密码错误」的响应时间差（见类注释第 2 点）
            passwordEncoder.matches(rawPassword, timingEqualizerHash);
            throw failure(name, "用户不存在");
        }

        if (!isEnabled(user)) {
            // 账号被禁用时同样陪跑，且不告诉调用方「这个号存在但被禁用了」
            passwordEncoder.matches(rawPassword, timingEqualizerHash);
            throw failure(name, "账号已禁用");
        }

        if (!passwordMatches(rawPassword, user.getPassword())) {
            throw failure(name, "密码错误");
        }

        // 登录成功必须清零，否则残留的失败次数会把下次一次手滑变成锁定
        clearFailures(name);
        log.info("登录成功 username={} userId={}", name, user.getId());
        return user;
    }

    // ==================== 注册 ====================

    @Override
    public Long register(String username, String rawPassword, String nickname) {
        String name = normalizeUsername(username);
        if (!isValidUsername(name)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "用户名需为 " + MIN_USERNAME_LENGTH + "~" + MAX_USERNAME_LENGTH + " 位字母、数字或下划线");
        }
        if (!isValidPassword(rawPassword)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "密码长度需为 " + MIN_PASSWORD_LENGTH + "~" + MAX_PASSWORD_LENGTH + " 位");
        }

        if (findByUsername(name) != null) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(name);
        // 只存哈希。明文密码在任何路径下都不该落到数据库、日志或返回值里
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname == null || nickname.isBlank() ? name : nickname.trim());
        user.setStatus(CommonConstants.STATUS_ENABLED);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 上面的存在性检查和这次 insert 之间有一个窗口期：两个请求同时注册同名账号时，
            // 先到的插入成功，后到的会撞上 t_user.username 的唯一索引。
            // 把这个数据库异常翻译成同一个业务错误，调用方就不必区分
            // 「检查时已存在」和「插入时已存在」—— 那两件事对用户是同一件事。
            log.info("注册并发冲突，用户名已被占用 username={}", name);
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        log.info("注册成功 username={} userId={}", name, user.getId());
        return user.getId();
    }

    // ==================== 可单测的纯函数 ====================

    /** 去掉首尾空白。用户从手机键盘输入时很容易带上空格，不处理会导致「明明密码对却登不上」。 */
    static String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    static boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    static boolean isValidPassword(String rawPassword) {
        if (rawPassword == null) {
            return false;
        }
        int length = rawPassword.length();
        return length >= MIN_PASSWORD_LENGTH && length <= MAX_PASSWORD_LENGTH;
    }

    // ==================== 内部工具 ====================

    private User findByUsername(String username) {
        if (username.isEmpty()) {
            return null;
        }
        // t_user.username 上有唯一索引，正常最多一条；last("limit 1") 是防御性的 ——
        // selectOne 在命中多行时会直接抛异常，那样一个脏数据就能让整个登录接口挂掉
        return userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .last("limit 1"));
    }

    private boolean isEnabled(User user) {
        return user.getStatus() != null && user.getStatus() == CommonConstants.STATUS_ENABLED;
    }

    /**
     * 密码比对。
     *
     * <p>先按 {@link #BCRYPT_HASH_PATTERN} 校验存储值的格式，不合格直接返回 false，
     * <b>不交给 {@code matches()}</b>。两个理由：
     *
     * <ol>
     *   <li>种子数据里如果还留着 {@code $2a$10$PLACEHOLDER_PLEASE_REPLACE_WITH_BCRYPT}
     *       这类占位符，{@code matches()} 会打一行
     *       "Encoded password does not look like BCrypt" 的警告。
     *       登录是最高频的接口，这种警告会把日志刷满，真正的异常反而被埋掉。</li>
     *   <li>把「存储值是否合法」这件事显式表达出来。只判 {@code isBlank()} 挡不住
     *       「长度对但内容是垃圾」的脏数据 —— 而那种数据每次登录都要白跑一次
     *       BCrypt（几十毫秒的 CPU），是可以被利用来消耗资源的。</li>
     * </ol>
     *
     * <p>注意这里**不是**安全边界：就算格式合法，能不能通过仍由 {@code matches()} 决定。
     * 格式校验只是把明显不可能是哈希的值提前摘出去，减少无谓计算与日志噪音。
     */
    private boolean passwordMatches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || !BCRYPT_HASH_PATTERN.matcher(encodedPassword).matches()) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    private long currentFailCount(String username) {
        Long count = cacheService.get(CommonConstants.CACHE_AUTH_FAIL + username, Long.class);
        return count == null ? 0L : count;
    }

    /** 记一次失败并返回要抛的异常。三种失败原因都走这里，保证对外表现完全一致。 */
    private BusinessException failure(String username, String reason) {
        long count = cacheService.increment(CommonConstants.CACHE_AUTH_FAIL + username, LOCK_DURATION);
        // 真实原因只进日志，不进响应体 —— 日志是给运维看的，响应体是给攻击者看的
        log.warn("登录失败 username={} 原因={} 累计失败={}/{}", username, reason, count, MAX_FAIL_COUNT);

        // 锁定判定刻意用 increment 的**返回值**，而不是再 get 一次计数器。
        // 原因：计数器的存储方式两种后端不一样（本地存 AtomicLong、Redis 存裸整数），
        // 读回来这件事依赖「裸数字恰好是合法 JSON」这种巧合。
        // 而 increment 的返回值是接口契约，不依赖任何存储细节 —— 用它判定最稳。
        if (count >= MAX_FAIL_COUNT) {
            return new BusinessException(ResultCode.AUTH_LOCKED,
                    "登录失败次数过多，请 " + LOCK_DURATION.toMinutes() + " 分钟后再试");
        }
        return new BusinessException(ResultCode.PASSWORD_ERROR);
    }

    private void clearFailures(String username) {
        cacheService.delete(CommonConstants.CACHE_AUTH_FAIL + username);
    }
}
