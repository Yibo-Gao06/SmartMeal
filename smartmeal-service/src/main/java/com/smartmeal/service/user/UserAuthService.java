package com.smartmeal.service.user;

import com.smartmeal.domain.entity.User;

/**
 * 账号认证。
 *
 * <p>刻意不返回 token：签发 token 是 Sa-Token（Web 层）的职责，
 * 而认证是业务规则。拆开之后，本接口的单元测试完全不需要 Web 上下文，
 * 也不需要 {@code StpUtil} 的静态状态 —— 那东西在测试里极难清理干净。
 *
 * <p>调用方（{@code AuthController}）拿到 {@link User} 后再 {@code StpUtil.login(user.getId())}。
 */
public interface UserAuthService {

    /**
     * 校验账号密码。
     *
     * <p>失败时抛 {@code BusinessException}，而不是返回 null 或 false。
     * 原因：失败的原因有三种（用户不存在 / 密码错 / 账号被禁用），
     * 但它们**必须对外表现完全一致**（见实现里的注释）。
     * 用异常可以让「失败」这件事无法被调用方忽略 —— 返回 null 时，
     * 总有人会忘记判空而放行。
     *
     * @return 认证通过的用户实体（含 id / nickname / 画像字段）
     * @throws com.smartmeal.common.exception.BusinessException 认证失败或账号被锁定
     */
    User authenticate(String username, String rawPassword);

    /**
     * 注册新账号。
     *
     * <p>只创建账号本身，不创建健康档案 —— 档案是「用户填了才算数」的东西，
     * 注册时给一堆默认值反而会让「档案不完整」这个状态永远检测不出来
     * （见 {@code ResultCode.PROFILE_INCOMPLETE} 的用途）。
     *
     * @return 新用户的 id
     * @throws com.smartmeal.common.exception.BusinessException 用户名已被占用或入参不合法
     */
    Long register(String username, String rawPassword, String nickname);
}
