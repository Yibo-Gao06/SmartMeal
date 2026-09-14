package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 用户表 t_user。身体数据直接冗余在主表，因为画像读取是最高频操作。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {

    private String username;
    /**
     * BCrypt 加密后的密码，绝不存明文。
     *
     * <p>用 {@code @ToString.Exclude} 挡掉：本项目所有日志都只打 {@code id} / {@code username}，
     * 但防御性的 —— 万一哪天有人写了 {@code log.debug("user={}", user)}，
     * Lombok 生成的 {@code toString()} 默认会把所有字段拼进去，哈希就会被打进日志。
     * 哈希不是明文，但它是「同一口令永远撞出同一哈希」的，进日志仍属不该发生的泄露。
     */
    @ToString.Exclude
    private String password;
    private String nickname;
    private String phone;
    private String avatar;

    /** 0 未知 / 1 男 / 2 女。 */
    private Integer gender;
    private LocalDate birthDate;

    private BigDecimal heightCm;
    private BigDecimal weightKg;

    /** low / middle / high，对应活动系数 1.2 / 1.55 / 1.725。 */
    private String activityLevel;

    /** loss_fat / gain_muscle / balance / low_sugar。 */
    private String goal;

    private BigDecimal weeklyBudget;
    private Integer familySize;
    private Integer status;
}
