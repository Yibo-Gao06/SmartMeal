package com.smartmeal.ai.validator;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验结果。
 *
 * <p>区分「硬错误」和「警告」是刻意的：
 * 过敏原命中是硬错误，必须整份拒绝；热量偏差 20% 只是警告，提示用户即可。
 * 如果一律硬失败，用户体验会变得很差，模型偶尔的小偏差也会导致整单重来。
 */
@Data
public class ValidationReport {

    /** 硬错误：存在即拒绝整份计划。 */
    private List<String> errors = new ArrayList<>();

    /** 警告：透传给前端展示，不阻断。 */
    private List<String> warnings = new ArrayList<>();

    public boolean hasError() {
        return !errors.isEmpty();
    }

    public void addError(String message) {
        errors.add(message);
    }

    public void addWarning(String message) {
        warnings.add(message);
    }

    public String errorSummary() {
        return String.join("; ", errors);
    }
}
