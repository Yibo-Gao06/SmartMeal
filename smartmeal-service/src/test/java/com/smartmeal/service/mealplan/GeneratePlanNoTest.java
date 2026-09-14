package com.smartmeal.service.mealplan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计划号生成。
 *
 * <p>格式约束：{@code MP + yyyyMMddHHmmss + 4 位随机数}，共 2 + 14 + 4 = 20 位。
 *
 * <p>这个测试存在的意义是钉住一个真实踩过的 bug：
 * 格式串含 HHmmss，但代码里用了 {@code LocalDate.now()} —— {@code LocalDate}
 * 没有 HourOfDay 字段，格式化直接抛 {@code UnsupportedTemporalTypeException}，
 * 导致整个 AI 生成链路在第一步就挂掉。改成 {@code LocalDateTime} 后，
 * 这里用「不抛异常 + 格式匹配」把约束钉死。
 */
class GeneratePlanNoTest {

    private static final Pattern PLAN_NO = Pattern.compile("^MP\\d{14}\\d{4}$");

    @Test
    @DisplayName("不抛异常（回归：LocalDate 格式化含 HHmmss 的格式串会抛 UnsupportedTemporalTypeException）")
    void doesNotThrow() {
        assertDoesNotThrow(MealPlanServiceImpl::generatePlanNo);
    }

    @Test
    @DisplayName("格式为 MP + 14 位时间戳 + 4 位随机数")
    void formatIsCorrect() {
        String planNo = MealPlanServiceImpl.generatePlanNo();

        assertTrue(PLAN_NO.matcher(planNo).matches(),
                "计划号应形如 MP20260913112835xxxx，实际为 " + planNo);
        assertTrue(planNo.length() == 20, "总长度应为 20");
    }

    @Test
    @DisplayName("时间戳部分是对当前时间的合法编码")
    void timestampPartIsValid() {
        String planNo = MealPlanServiceImpl.generatePlanNo();
        String ts = planNo.substring(2, 16);

        // 至少能解析成年月日时分秒，且月份在 1-12、日在 1-31 这种基本合法性由
        // DateTimeFormatter 保证；这里只做最粗的合理性检查
        assertTrue(Integer.parseInt(ts.substring(4, 6)) >= 1 && Integer.parseInt(ts.substring(4, 6)) <= 12,
                "月份应在 1-12 之间");
        assertTrue(Integer.parseInt(ts.substring(6, 8)) >= 1 && Integer.parseInt(ts.substring(6, 8)) <= 31,
                "日期应在 1-31 之间");
        assertTrue(Integer.parseInt(ts.substring(8, 10)) <= 23, "小时应在 0-23 之间");
        assertTrue(Integer.parseInt(ts.substring(10, 12)) <= 59, "分钟应在 0-59 之间");
    }
}
