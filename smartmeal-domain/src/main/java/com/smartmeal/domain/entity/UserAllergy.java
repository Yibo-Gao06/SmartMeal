package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户过敏原 t_user_allergy。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_allergy")
public class UserAllergy extends BaseEntity {

    private Long userId;
    /** 标准化过敏原编码，如 PEANUT / SEAFOOD / MILK，避免用中文名做匹配。 */
    private String allergenCode;
    private String allergenName;
    /** mild / moderate / severe。 */
    private String severity;
}
