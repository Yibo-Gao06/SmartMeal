package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 用户表 t_user。身体数据直接冗余在主表，因为画像读取是最高频操作。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {

    private String username;
    /** BCrypt 加密后的密码，绝不存明文。 */
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
