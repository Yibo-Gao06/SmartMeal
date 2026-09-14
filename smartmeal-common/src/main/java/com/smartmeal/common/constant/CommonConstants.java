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

    /**
     * 登录失败计数器前缀。
     *
     * <p>和 {@link #CACHE_RATE_AI} 分开而不是复用，因为两者的语义不同：
     * 限流是「时间窗内允许 N 次」，计数到点自动失效；
     * 登录失败是「累计错 N 次就锁一段时间」，**成功登录必须主动清零**。
     * 混用会让「输错 4 次后输对一次」这种正常行为把配额也一起吃掉。
     */
    public static final String CACHE_AUTH_FAIL = "smartmeal:auth:fail:";
}
