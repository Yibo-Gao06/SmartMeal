package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 用户冰箱库存 t_user_fridge_ingredient，用于生成购物清单时扣减。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_fridge_ingredient")
public class UserFridgeIngredient extends BaseEntity {

    private Long userId;
    private Long ingredientId;
    private String ingredientName;
    private BigDecimal amount;
    /** 统一换算为 g / ml / piece 后再入库。 */
    private String unit;
    private LocalDate expireDate;
}
