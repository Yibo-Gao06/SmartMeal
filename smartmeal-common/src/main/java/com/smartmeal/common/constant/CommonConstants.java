package com.smartmeal.common.constant;

/** 跨模块公共常量。 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /** 通用启用状态。 */
    public static final int STATUS_ENABLED = 1;
    public static final int STATUS_DISABLED = 0;

    /** Sa-Token 用户端 / 管理端登录类型，用于隔离两套会话。 */
    public static final String LOGIN_TYPE_APP = "app";
    public static final String LOGIN_TYPE_ADMIN = "admin";

    /** 请求头。 */
    public static final String HEADER_TOKEN = "satoken";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 缓存 key 前缀。 */
    public static final String CACHE_USER_PROFILE = "smartmeal:user:profile:";
    public static final String CACHE_SKU = "smartmeal:product:sku:";
    public static final String CACHE_CATEGORY_TREE = "smartmeal:category:tree";
    public static final String CACHE_PROMPT_TEMPLATE = "smartmeal:prompt:template:";
    public static final String CACHE_RAG_RESULT = "smartmeal:rag:retrieve:";
    public static final String CACHE_AI_TASK = "smartmeal:ai:task:";
    public static final String CACHE_AI_PLAN_RESULT = "smartmeal:ai:plan:";
    public static final String CACHE_RATE_AI = "smartmeal:rate:ai:";
}
