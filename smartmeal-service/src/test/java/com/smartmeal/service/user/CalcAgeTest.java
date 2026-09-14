package com.smartmeal.service.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 出生日期 → 年龄 的换算。
 *
 * <p>表里存的是 {@code birth_date} 而不是年龄（年龄存下来第二年就错了），
 * 所以每次构建画像都要现算。这里最容易写错的是「今年生日还没到」的情况 ——
 * 直接拿年份相减会多算一岁，而多算一岁会让 BMR 偏高、热量目标偏高。
 *
 * <p>之所以单独测这个换算：一旦算错，{@code NutritionCalculator} 的整条链路
 * 都会给出偏高的热量，而用户完全看不出来。
 */
class CalcAgeTest {

    @Test
    @DisplayName("今年生日已过：按周岁算")
    void birthdayAlreadyPassed() {
        assertEquals(26, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 5, 20), LocalDate.of(2026, 9, 13)));
    }

    @Test
    @DisplayName("今年生日还没到：不能算成 26 岁")
    void birthdayNotYetReached() {
        assertEquals(25, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 5, 20), LocalDate.of(2026, 3, 1)));
    }

    @Test
    @DisplayName("正好生日当天：算作已满")
    void exactlyOnBirthday() {
        assertEquals(26, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 5, 20), LocalDate.of(2026, 5, 20)));
    }

    @Test
    @DisplayName("生日前一天：仍是上一岁")
    void dayBeforeBirthday() {
        assertEquals(25, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 5, 20), LocalDate.of(2026, 5, 19)));
    }

    @Test
    @DisplayName("2 月 29 日出生：非闰年也能正常推算，不抛异常")
    void leapDayBirthday() {
        assertEquals(25, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 2, 29), LocalDate.of(2026, 2, 28)));
        assertEquals(26, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 2, 29), LocalDate.of(2026, 3, 1)));
    }

    @Test
    @DisplayName("出生日期为空：返回 null，交给 NutritionCalculator 走兜底分支")
    void nullBirthDate() {
        assertNull(UserProfileServiceImpl.calcAge(null, LocalDate.of(2026, 9, 13)));
    }

    @Test
    @DisplayName("演示用户：2000-05-20 出生，2026-09-13 时是 26 岁")
    void demoUser() {
        // 这个值会直接进入 Mifflin-St Jeor 公式，与 NutritionCalculatorTest 里的
        // 手算期望值（BMR 1718）用的是同一套前提，改动年龄会同时影响两处
        assertEquals(26, UserProfileServiceImpl.calcAge(
                LocalDate.of(2000, 5, 20), LocalDate.of(2026, 9, 13)));
    }
}
