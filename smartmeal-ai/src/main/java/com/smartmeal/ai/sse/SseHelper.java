package com.smartmeal.ai.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE 发送封装。
 *
 * <p>为什么要包一层：{@link SseEmitter#send} 在客户端断开时会抛
 * {@link IOException} 或 {@code IllegalStateException}（响应已提交）。
 * 这两个异常在流式场景里是<b>常态</b>而不是故障 —— 用户切后台、关页面都会触发。
 * 如果不吞掉，日志会被刷爆，异常还会往上冒泡打断生成流程。
 *
 * <p>做法：统一捕获并返回 false，由调用方决定是否提前终止生成以省 token。
 */
@Slf4j
public class SseHelper {

    private final SseEmitter emitter;
    private volatile boolean clientGone = false;

    public SseHelper(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /** 客户端是否已断开。生成过程中定期检查，可以提前止损。 */
    public boolean isClientGone() {
        return clientGone;
    }

    public boolean send(SseEventType type, Object data) {
        if (clientGone) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(type.eventName()).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            // 客户端断开：标记后静默返回，不打印堆栈
            clientGone = true;
            log.debug("SSE 客户端已断开，事件类型={}", type.eventName());
            return false;
        }
    }

    public boolean sendProgress(String stage, String message) {
        return send(SseEventType.PROGRESS, Map.of("stage", stage, "message", message));
    }

    public boolean sendToken(String content) {
        return send(SseEventType.TOKEN, Map.of("content", content));
    }

    public boolean sendResult(Object data) {
        return send(SseEventType.RESULT, data);
    }

    public boolean sendDone() {
        return send(SseEventType.DONE, Map.of("success", true));
    }

    public boolean sendError(Integer code, String message) {
        return send(SseEventType.ERROR, Map.of("code", code, "message", message));
    }

    public boolean sendHeartbeat() {
        return send(SseEventType.HEARTBEAT, Map.of("ts", System.currentTimeMillis()));
    }

    /** 结束连接。重复调用是安全的。 */
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete 异常（通常可忽略）: {}", e.getMessage());
        }
    }

    public void completeWithError(Throwable t) {
        try {
            emitter.completeWithError(t);
        } catch (Exception e) {
            log.debug("SSE completeWithError 异常（通常可忽略）: {}", e.getMessage());
        }
    }
}
