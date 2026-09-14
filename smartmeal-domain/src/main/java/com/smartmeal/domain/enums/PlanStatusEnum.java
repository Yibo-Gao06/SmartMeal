package com.smartmeal.domain.enums;

import lombok.Getter;

/**
 * 膳食计划生成状态机。
 *
 * <pre>
 * GENERATING → SUCCESS
 *            ↘ FAILED
 *            ↘ FALLBACK（降级为模板食谱，仍然算可用结果）
 * </pre>
 */
@Getter
public enum PlanStatusEnum {

    GENERATING("GENERATING", "生成中"),
    SUCCESS("SUCCESS", "生成成功"),
    FALLBACK("FALLBACK", "降级模板"),
    FAILED("FAILED", "生成失败");

    private final String code;
    private final String label;

    PlanStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public boolean isTerminal() {
        return this != GENERATING;
    }
}
