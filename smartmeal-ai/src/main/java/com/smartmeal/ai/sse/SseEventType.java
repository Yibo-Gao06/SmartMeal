package com.smartmeal.ai.sse;

/**
 * SSE 事件类型。
 *
 * <p>把「进度」和「内容」分成两类事件，是因为前端要区别对待：
 * 进度进状态栏，内容进打字机区域。混在一起前端只能靠猜。
 *
 * <pre>
 * event: progress   {"stage":"RETRIEVE","message":"正在检索营养知识库"}
 * event: token      {"content":"根据您的身体数据"}
 * event: result     {"planId":1001,"shoppingListId":2001}
 * event: done       {"success":true}
 * event: error      {"code":20004,"message":"..."}
 * event: heartbeat  {}
 * </pre>
 */
public enum SseEventType {

    /** 阶段进度。 */
    PROGRESS("progress"),
    /** 增量文本。 */
    TOKEN("token"),
    /** 最终结构化结果（计划 ID、清单 ID）。 */
    RESULT("result"),
    /** 正常结束。 */
    DONE("done"),
    /** 异常结束。 */
    ERROR("error"),
    /** 心跳，用于穿透 Nginx 与浏览器的空闲超时。 */
    HEARTBEAT("heartbeat");

    private final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
