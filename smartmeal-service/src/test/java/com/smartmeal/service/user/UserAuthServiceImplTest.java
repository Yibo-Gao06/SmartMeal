package com.smartmeal.service.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmeal.common.cache.CacheService;
import com.smartmeal.common.cache.LocalCacheService;
import com.smartmeal.common.constant.CommonConstants;
import com.smartmeal.common.exception.BusinessException;
import com.smartmeal.common.result.ResultCode;
import com.smartmeal.domain.entity.User;
import com.smartmeal.repository.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录 / 注册逻辑的单元测试。
 *
 * <p><b>只 mock 一个 {@code UserMapper}</b>：密码哈希和失败计数这两块不是「外部依赖」，
 * 它们本身就是被测对象 —— 把 {@code PasswordEncoder} 或 {@code CacheService} mock 掉，
 * 等于把要验的东西先假设成对的。所以这里用真实的 {@link BCryptPasswordEncoder}
 * 和真实的 {@link LocalCacheService}（Caffeine 本地实现，不需要 Redis，也不需要数据库）。
 *
 * <p><b>为什么不去断言 Wrapper 里的参数</b>：MyBatis-Plus 3.5.x 把条件参数的填充推迟到
 * SQL 生成阶段，只调用了 {@code eq(...)} 之后 {@code getParamNameValuePairs()} 仍是空 Map，
 * 强行读 {@code getSqlSegment()} 还会因 TableInfo 未初始化而抛 {@code can not find lambda cache}。
 * 好在每个用例只喂一种数据，mock 不需要按参数过滤也能正确返回。
 *
 * <p><b>每个用例自己写 stub，不放在 {@code @BeforeEach} 里</b>：Mockito 默认的
 * STRICT_STUBS 会把「定义了但没被调用」的 stub 判为失败，而「账号已锁定」「参数非法」
 * 这类用例会提前返回、根本不碰数据库。把 stub 写在用例内部，能让严格模式真正起到
 * 「帮我发现多余的 mock」的作用，而不是被迫全局放开。
 */
@ExtendWith(MockitoExtension.class)
class UserAuthServiceImplTest {

    /** 与种子数据、README 里演示账号保持一致的明文口令。 */
    private static final String RAW_PASSWORD = "demo123456";

    /**
     * 真实编码器，strength 用默认的 10（与 {@code PasswordConfig} 的生产配置一致）。
     * 类级别静态复用：BCrypt 一次要几十毫秒，每个用例重算会把整个套件拖慢一个量级。
     */
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** 真实哈希。BCrypt 每次 encode 的 salt 不同，但任意一个都能校验通过。 */
    private static final String DEMO_HASH = ENCODER.encode(RAW_PASSWORD);

    @Mock
    private UserMapper userMapper;

    private CacheService cacheService;

    private UserAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        cacheService = new LocalCacheService(new ObjectMapper(), 1000);
        service = new UserAuthServiceImpl(userMapper, ENCODER, cacheService);
    }

    // ==================== 测试数据与工具 ====================

    private static User demoUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("demo");
        user.setPassword(DEMO_HASH);
        user.setNickname("演示用户");
        user.setStatus(CommonConstants.STATUS_ENABLED);
        return user;
    }

    private static User disabledUser() {
        User user = demoUser();
        user.setStatus(CommonConstants.STATUS_DISABLED);
        return user;
    }

    /** 跑一段预期抛业务异常的代码，把异常取回来断言。 */
    private static BusinessException failWith(Executable executable) {
        return assertThrows(BusinessException.class, executable);
    }

    /** 模拟 MyBatis-Plus 回填自增主键：不这么做的话 insert 之后拿到的 id 是 null。 */
    private void stubInsertAssignsId(long id) {
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, User.class).setId(id);
            return 1;
        });
    }

    private User capturedInsertedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 登录：成功路径 ====================

    @Nested
    @DisplayName("登录 —— 成功路径")
    class LoginSuccess {

        @Test
        @DisplayName("密码正确时返回用户，且返回值里没有明文口令")
        void correctPasswordReturnsUser() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            User user = service.authenticate("demo", RAW_PASSWORD);

            assertEquals(1L, user.getId());
            assertEquals("demo", user.getUsername());
            assertNotEquals(RAW_PASSWORD, user.getPassword(), "返回值里不该出现明文口令");
        }

        @Test
        @DisplayName("用户名首尾空白被忽略：手机键盘输入很容易带上空格")
        void usernameIsTrimmed() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            User user = service.authenticate("  demo  ", RAW_PASSWORD);

            assertEquals("demo", user.getUsername());
        }
    }

    // ==================== 登录：防用户名枚举 ====================

    @Nested
    @DisplayName("登录 —— 防用户名枚举")
    class AntiEnumeration {

        /*
         * 下面两个用例都断言「错误码与文案恰好等于 ResultCode.PASSWORD_ERROR 的常量值」。
         * 因为比较的是同一个常量，两个用例一起看就等价于「两条路径对外完全一致」，
         * 而且比「把两次异常互相比较」更直白 —— 后者需要重新打桩，容易被 mock 的
         * 严格模式挑刺，也让用例的意图被测试脚手架盖住。
         */

        @Test
        @DisplayName("用户不存在：对外就是普通的「用户名或密码错误」")
        void unknownUserLooksLikeWrongPassword() {
            when(userMapper.selectOne(any())).thenReturn(null);

            BusinessException e = failWith(() -> service.authenticate("ghost", RAW_PASSWORD));

            assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode(),
                    "给「用户不存在」单独一个错误码，登录接口就变成了用户名枚举器");
            assertEquals(ResultCode.PASSWORD_ERROR.getMessage(), e.getMessage(),
                    "文案也要一致 —— 攻击者同样能靠文案差异筛出真实存在的账号");
        }

        @Test
        @DisplayName("密码错误：对外同样是「用户名或密码错误」")
        void wrongPasswordLooksLikeItself() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            BusinessException e = failWith(() -> service.authenticate("demo", "wrong-password"));

            assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode());
            assertEquals(ResultCode.PASSWORD_ERROR.getMessage(), e.getMessage());
        }

        @Test
        @DisplayName("账号被禁用：即使密码正确也拒绝，且表现与密码错误完全一致")
        void disabledUserIsRejectedLikeWrongPassword() {
            when(userMapper.selectOne(any())).thenReturn(disabledUser());

            BusinessException e = failWith(() -> service.authenticate("demo", RAW_PASSWORD));

            assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode(),
                    "不能告诉调用方「这个号存在但被禁用了」—— 那也是一条枚举线索");
            assertEquals(ResultCode.PASSWORD_ERROR.getMessage(), e.getMessage());
        }

        @Test
        @DisplayName("status 为 null 的脏数据按禁用处理，不放行")
        void nullStatusIsTreatedAsDisabled() {
            User broken = demoUser();
            broken.setStatus(null);
            when(userMapper.selectOne(any())).thenReturn(broken);

            assertThrows(BusinessException.class,
                    () -> service.authenticate("demo", RAW_PASSWORD));
        }

        @Test
        @DisplayName("用户名为空时不查库，直接按密码错误拒绝")
        void emptyUsernameSkipsDatabase() {
            BusinessException e = failWith(() -> service.authenticate("", RAW_PASSWORD));

            assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode());
            verify(userMapper, never()).selectOne(any());
        }

        @Test
        @DisplayName("库里存的是种子占位符时直接拒绝：不消耗一次 BCrypt，也不会刷出格式警告")
        void placeholderHashIsRejectedBeforeHittingBcrypt() {
            /*
             * 这里刻意换成一个 mock 的编码器当「探针」。
             * 光断言「抛了 BusinessException」是不够的 —— 那样即使把占位符交给 matches()、
             * 由 BCrypt 返回 false，用例也照样是绿的。而真正要守的行为是
             * 「根本不该走到 BCrypt 那一步」，只有 verify(never()) 能证明。
             */
            PasswordEncoder probe = mock(PasswordEncoder.class);
            UserAuthServiceImpl guarded = new UserAuthServiceImpl(userMapper, probe, cacheService);

            User placeholder = demoUser();
            placeholder.setPassword("$2a$10$PLACEHOLDER_PLEASE_REPLACE_WITH_BCRYPT");
            when(userMapper.selectOne(any())).thenReturn(placeholder);

            BusinessException e = failWith(() -> guarded.authenticate("demo", RAW_PASSWORD));

            assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode());
            verify(probe, never()).matches(anyString(), anyString());
        }

        @Test
        @DisplayName("长度对但内容是垃圾的哈希同样被格式校验摘掉，不白跑 BCrypt")
        void malformedHashIsRejectedByFormatCheck() {
            PasswordEncoder probe = mock(PasswordEncoder.class);
            UserAuthServiceImpl guarded = new UserAuthServiceImpl(userMapper, probe, cacheService);

            User broken = demoUser();
            // 长度接近 60，但不是合法 BCrypt：下划线不在 BCrypt 的 base64 字母表里
            broken.setPassword("$2a$10$" + "_".repeat(53));
            when(userMapper.selectOne(any())).thenReturn(broken);

            assertThrows(BusinessException.class, () -> guarded.authenticate("demo", RAW_PASSWORD));
            verify(probe, never()).matches(anyString(), anyString());
        }
    }

    // ==================== 登录：失败计数与锁定 ====================

    @Nested
    @DisplayName("登录 —— 失败计数与锁定")
    class LoginLock {

        @Test
        @DisplayName("第 1~4 次失败返回密码错误，第 5 次起返回锁定，提示里写明要等多久")
        void lockTriggersOnFifthFailure() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            for (int i = 1; i <= 4; i++) {
                BusinessException e = failWith(() -> service.authenticate("demo", "wrong-password"));
                assertEquals(ResultCode.PASSWORD_ERROR.getCode(), e.getCode(),
                        "第 " + i + " 次失败不该锁定，用户还有机会");
            }

            BusinessException fifth = failWith(() -> service.authenticate("demo", "wrong-password"));
            assertEquals(ResultCode.AUTH_LOCKED.getCode(), fifth.getCode(),
                    "累计到第 5 次应触发锁定");
            assertTrue(fifth.getMessage().contains("15"),
                    "锁定提示要写清等多久，实际文案：" + fifth.getMessage());
        }

        @Test
        @DisplayName("锁定期内即使密码正确也被拒绝，且不再查库")
        void lockedAccountRejectsEvenCorrectPassword() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            for (int i = 0; i < 5; i++) {
                assertThrows(BusinessException.class,
                        () -> service.authenticate("demo", "wrong-password"));
            }

            BusinessException e = failWith(() -> service.authenticate("demo", RAW_PASSWORD));

            assertEquals(ResultCode.AUTH_LOCKED.getCode(), e.getCode(),
                    "锁定必须挡得住正确密码，否则锁定毫无意义");
            // 提前拒绝的意义：连数据库都不碰。否则锁定只能防「猜对密码」，
            // 防不住「拿一个已锁死的用户名把数据库连接池打满」。
            // 前 5 次各查了一次，第 6 次不该再查 —— 所以是 times(5) 而不是 never()
            verify(userMapper, times(5)).selectOne(any());
        }

        @Test
        @DisplayName("登录成功清零失败计数：错 4 次后输对一次，下次手滑不会直接被锁")
        void successResetsFailCounter() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            for (int i = 0; i < 4; i++) {
                assertThrows(BusinessException.class,
                        () -> service.authenticate("demo", "wrong-password"));
            }

            // 第 5 次输对。计数器里有 4，但还没到上限，应该放行
            assertNotNull(service.authenticate("demo", RAW_PASSWORD));

            // 计数已归零：再错一次应该还是「密码错误」，而不是直接「已锁定」
            BusinessException after = failWith(() -> service.authenticate("demo", "wrong-password"));
            assertEquals(ResultCode.PASSWORD_ERROR.getCode(), after.getCode(),
                    "成功登录没清零的话，这里会变成锁定 —— 用户会以为系统坏了，其实是计数器语义没想清楚");
        }

        @Test
        @DisplayName("失败计数按用户名隔离：缓存 key 是逐个用户独立的一份")
        void failCounterIsPerUsername() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            for (int i = 0; i < 5; i++) {
                assertThrows(BusinessException.class,
                        () -> service.authenticate("demo", "wrong-password"));
            }

            // 直接查缓存，验证 key 的构造方式：前缀 + 用户名。
            // 顺带钉住 CacheService.get(key, Long.class) 在本地实现下能读回计数值 ——
            // 本地存的是 AtomicLong 对象，靠 convertNumber 转换，这条链路值得有个断言守着。
            assertEquals(5L, cacheService.get(CommonConstants.CACHE_AUTH_FAIL + "demo", Long.class));
            assertNull(cacheService.get(CommonConstants.CACHE_AUTH_FAIL + "alice", Long.class),
                    "别的用户名的计数器应该是空的，不能被 demo 的失败连累");
        }
    }

    // ==================== 注册 ====================

    @Nested
    @DisplayName("注册")
    class Register {

        @Test
        @DisplayName("注册成功：返回自增 ID，落库的是哈希而非明文")
        void registerStoresHashNotPlaintext() {
            when(userMapper.selectOne(any())).thenReturn(null);
            stubInsertAssignsId(100L);

            Long userId = service.register("newbie", RAW_PASSWORD, "新用户");

            assertEquals(100L, userId);

            User saved = capturedInsertedUser();
            assertEquals("newbie", saved.getUsername());
            assertEquals("新用户", saved.getNickname());
            assertEquals(CommonConstants.STATUS_ENABLED, saved.getStatus());
            assertNotEquals(RAW_PASSWORD, saved.getPassword(), "明文口令绝不能落库");
            assertTrue(saved.getPassword().startsWith("$2a$"),
                    "落库的应是 BCrypt 哈希，实际是：" + saved.getPassword());
            assertTrue(ENCODER.matches(RAW_PASSWORD, saved.getPassword()),
                    "落库的哈希必须能用原口令校验通过，否则用户注册完就登不进去");
        }

        @Test
        @DisplayName("昵称为空时回落为用户名，不留 null")
        void blankNicknameFallsBackToUsername() {
            when(userMapper.selectOne(any())).thenReturn(null);
            stubInsertAssignsId(100L);

            service.register("newbie", RAW_PASSWORD, "   ");

            assertEquals("newbie", capturedInsertedUser().getNickname());
        }

        @Test
        @DisplayName("用户名已被占用时拒绝，且不再尝试插入")
        void duplicateUsernameRejectedBeforeInsert() {
            when(userMapper.selectOne(any())).thenReturn(demoUser());

            BusinessException e = failWith(() -> service.register("demo", RAW_PASSWORD, null));

            assertEquals(ResultCode.USER_ALREADY_EXISTS.getCode(), e.getCode());
            verify(userMapper, never()).insert(any(User.class));
        }

        @Test
        @DisplayName("并发注册撞上唯一索引时，翻译成同一个业务错误而不是 500")
        void duplicateKeyExceptionIsTranslated() {
            when(userMapper.selectOne(any())).thenReturn(null);
            when(userMapper.insert(any(User.class)))
                    .thenThrow(new DuplicateKeyException("Duplicate entry 'demo' for key 'uk_username'"));

            BusinessException e = failWith(() -> service.register("demo", RAW_PASSWORD, null));

            assertEquals(ResultCode.USER_ALREADY_EXISTS.getCode(), e.getCode(),
                    "「检查时已存在」和「插入时已存在」对用户是同一件事，不该暴露成系统异常");
        }

        @Test
        @DisplayName("弱密码被拒绝：7 位不行，8 位刚好可以")
        void passwordLengthBoundary() {
            when(userMapper.selectOne(any())).thenReturn(null);
            stubInsertAssignsId(100L);

            BusinessException e = failWith(() -> service.register("newbie", "1234567", null));
            assertEquals(ResultCode.BAD_REQUEST.getCode(), e.getCode());

            assertNotNull(service.register("newbie", "12345678", null));
        }

        @Test
        @DisplayName("超长密码被拒绝：BCrypt 只读前 72 字节，64 字符是明确的上限")
        void passwordTooLongIsRejected() {
            BusinessException e = failWith(() -> service.register("newbie", "a".repeat(65), null));

            assertEquals(ResultCode.BAD_REQUEST.getCode(), e.getCode());
            verify(userMapper, never()).insert(any(User.class));
        }

        @Test
        @DisplayName("非法用户名被拒绝：太短、含中文、含空格、含连字符或点号")
        void invalidUsernamesAreRejected() {
            for (String bad : new String[]{"ab", "高同学", "demo user", "demo-user", "demo.user"}) {
                BusinessException e = failWith(() -> service.register(bad, RAW_PASSWORD, null));
                assertEquals(ResultCode.BAD_REQUEST.getCode(), e.getCode(),
                        "用户名 " + bad + " 应被拒绝");
            }
            // 校验不过就不该白查一次库
            verify(userMapper, never()).selectOne(any());
            verify(userMapper, never()).insert(any(User.class));
        }

        @Test
        @DisplayName("注册时用户名首尾空白先去掉再落库")
        void usernameTrimmedOnRegister() {
            when(userMapper.selectOne(any())).thenReturn(null);
            stubInsertAssignsId(100L);

            service.register("  newbie  ", RAW_PASSWORD, null);

            assertEquals("newbie", capturedInsertedUser().getUsername());
        }
    }

    // ==================== 纯函数与 BCrypt 行为 ====================

    @Nested
    @DisplayName("纯函数与 BCrypt 行为")
    class PureFunctions {

        @Test
        @DisplayName("normalizeUsername 把 null 归一成空串，而不是留着 null")
        void normalizeUsernameHandlesNull() {
            assertEquals("", UserAuthServiceImpl.normalizeUsername(null));
            assertEquals("", UserAuthServiceImpl.normalizeUsername("   "));
            assertEquals("demo", UserAuthServiceImpl.normalizeUsername("  demo  "));
            assertEquals("demo", UserAuthServiceImpl.normalizeUsername("demo"));
        }

        @Test
        @DisplayName("isValidUsername：3~64 位字母数字下划线，边界逐个钉死")
        void isValidUsernameBoundaries() {
            assertFalse(UserAuthServiceImpl.isValidUsername(null));
            assertFalse(UserAuthServiceImpl.isValidUsername("ab"), "2 位太短");
            assertTrue(UserAuthServiceImpl.isValidUsername("abc"), "3 位刚好合法");
            assertTrue(UserAuthServiceImpl.isValidUsername("a".repeat(64)), "64 位刚好合法");
            assertFalse(UserAuthServiceImpl.isValidUsername("a".repeat(65)), "65 位超长");
            assertTrue(UserAuthServiceImpl.isValidUsername("demo_01"));
            assertFalse(UserAuthServiceImpl.isValidUsername("demo 01"));
            assertFalse(UserAuthServiceImpl.isValidUsername("demo-01"));
        }

        @Test
        @DisplayName("isValidPassword：8~64 位，null 与空串都拒绝")
        void isValidPasswordBoundaries() {
            assertFalse(UserAuthServiceImpl.isValidPassword(null));
            assertFalse(UserAuthServiceImpl.isValidPassword(""));
            assertFalse(UserAuthServiceImpl.isValidPassword("a".repeat(7)));
            assertTrue(UserAuthServiceImpl.isValidPassword("a".repeat(8)));
            assertTrue(UserAuthServiceImpl.isValidPassword("a".repeat(64)));
            assertFalse(UserAuthServiceImpl.isValidPassword("a".repeat(65)));
        }

        @Test
        @DisplayName("BCrypt 的 salt 编码在哈希串自身里：同口令两次 encode 结果不同，但都能校验")
        void bcryptSaltIsEmbeddedInHash() {
            String first = ENCODER.encode(RAW_PASSWORD);
            String second = ENCODER.encode(RAW_PASSWORD);

            assertNotEquals(first, second, "每次 encode 应使用随机 salt，否则相同口令会撞出相同哈希");
            assertEquals(60, first.length(), "BCrypt 哈希固定 60 字符，varchar(128) 绰绰有余");
            assertTrue(first.startsWith("$2a$10$"), "算法标识 + 轮数前缀应固定");
            assertTrue(ENCODER.matches(RAW_PASSWORD, first));
            assertTrue(ENCODER.matches(RAW_PASSWORD, second));
            assertFalse(ENCODER.matches(RAW_PASSWORD + "x", first));
        }
    }
}
