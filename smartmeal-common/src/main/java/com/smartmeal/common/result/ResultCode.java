package com.smartmeal.common.result;

import lombok.Getter;

/**
 * 全局返回码。
 *
 * <p>约定：
 * <ul>
 *   <li>2xx / 4xx / 5xx：与 HTTP 语义对齐的通用码</li>
 *   <li>1xxxx：用户与交易域</li>
 *   <li>2xxxx：AI 规划域</li>
 * </ul>
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后重试"),
    ERROR(500, "系统异常，请稍后重试"),

    // ===== 1xxxx 用户与交易 =====
    USER_NOT_FOUND(10001, "用户不存在"),
    USER_ALREADY_EXISTS(10002, "用户名已被占用"),
    PASSWORD_ERROR(10003, "用户名或密码错误"),
    PROFILE_INCOMPLETE(10004, "健康档案不完整，请先补全身体数据"),

    INGREDIENT_NOT_FOUND(11001, "食材不存在"),
    RECIPE_NOT_FOUND(11002, "菜谱不存在"),

    SKU_NOT_FOUND(12001, "商品不存在或已下架"),
    SKU_OUT_OF_STOCK(12002, "商品库存不足"),
    CART_EMPTY(12003, "购物车为空"),
    CART_ITEM_NOT_FOUND(12004, "购物车条目不存在"),

    ORDER_NOT_FOUND(13001, "订单不存在"),
    ORDER_STATUS_ILLEGAL(13002, "订单状态不允许该操作"),
    ORDER_AMOUNT_MISMATCH(13003, "订单金额校验失败"),

    // ===== 2xxxx AI 规划 =====
    AI_GENERATE_FAILED(20001, "AI 生成失败，请稍后重试"),
    AI_PARSE_FAILED(20002, "AI 返回结果解析失败"),
    AI_SCHEMA_INVALID(20003, "AI 返回结果不符合结构约束"),
    AI_ALLERGEN_HIT(20004, "检测到过敏原，已拒绝该方案"),
    AI_NUTRITION_OUT_OF_RANGE(20005, "热量偏离目标区间过大"),
    AI_RATE_LIMITED(20006, "生成请求过于频繁，请稍后再试"),
    AI_DUPLICATE_REQUEST(20007, "重复请求，已返回进行中的任务"),
    AI_TASK_NOT_FOUND(20008, "生成任务不存在或已过期"),
    AI_TEMPLATE_FALLBACK(20009, "AI 服务暂不可用，已降级为模板食谱");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
