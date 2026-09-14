package com.smartmeal.ai.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进行中任务的注册表。
 *
 * <p>解决的问题：用户发起生成后网络断了 / 刷新了页面 / 切到后台，
 * 回来时想知道「我的计划生成到哪一步了」。
 *
 * <p>如果只把状态放数据库，每次查询都要落盘，而且高频更新进度会带来无谓的写压力。
 * 这里用内存做热数据，终态再落库，是典型的「热路径内存、冷路径落库」组合。
 *
 * <p><b>注意</b>：内存态在多实例部署时会失效（用户重连可能打到另一个实例）。
 * 生产环境应把这张表换成 Redis，key 为 {@code smartmeal:ai:task:{requestId}}，
 * 配合 {@code CacheService} 即可，接口不用改。
 */
@Slf4j
@Component
public class MealPlanTaskRegistry {

    /** 最多保留的任务数，超出后按时间淘汰最旧的，防止内存无限增长。 */
    private static final int MAX_TASKS = 5_000;

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();

    public void register(String requestId, Long planId, Long userId) {
        if (tasks.size() >= MAX_TASKS) {
            evictOldest();
        }
        TaskState state = new TaskState();
        state.setRequestId(requestId);
        state.setPlanId(planId);
        state.setUserId(userId);
        state.setStage("INIT");
        state.setFinished(false);
        state.setStartedAt(Instant.now());
        state.setUpdatedAt(Instant.now());
        tasks.put(requestId, state);
    }

    public void updateStage(String requestId, String stage) {
        TaskState state = tasks.get(requestId);
        if (state != null) {
            state.setStage(stage);
            state.setUpdatedAt(Instant.now());
        }
    }

    public void complete(String requestId, Long planId, Long shoppingListId) {
        TaskState state = tasks.get(requestId);
        if (state != null) {
            state.setStage("SUCCESS");
            state.setFinished(true);
            state.setPlanId(planId);
            state.setShoppingListId(shoppingListId);
            state.setUpdatedAt(Instant.now());
        }
    }

    public void fail(String requestId, String errorMsg) {
        TaskState state = tasks.get(requestId);
        if (state != null) {
            state.setStage("FAILED");
            state.setFinished(true);
            state.setErrorMsg(errorMsg);
            state.setUpdatedAt(Instant.now());
        }
    }

    public TaskState get(String requestId) {
        return tasks.get(requestId);
    }

    /** 只淘汰已经结束的任务，绝不打断进行中的生成。 */
    private void evictOldest() {
        tasks.values().stream()
                .filter(TaskState::isFinished)
                .min(Comparator.comparing(TaskState::getUpdatedAt))
                .ifPresent(oldest -> tasks.remove(oldest.getRequestId()));
    }

    /** 任务状态快照，直接作为查询接口的返回体。 */
    @Data
    public static class TaskState {
        private String requestId;
        private Long userId;
        private Long planId;
        private Long shoppingListId;
        private String stage;
        private boolean finished;
        private String errorMsg;
        private Instant startedAt;
        private Instant updatedAt;
    }
}
