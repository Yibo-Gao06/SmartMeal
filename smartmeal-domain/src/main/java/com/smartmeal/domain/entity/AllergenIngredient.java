package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 过敏原 → 食材映射表 t_allergen_ingredient。
 *
 * <p><b>这张表是安全校验的地基。</b>只做字符串匹配是不够的：
 * 用户过敏「花生」，模型生成的菜谱里写的是「花生油」「花生酱」「花生碎」，
 * 纯字符串比较会漏判。所以必须维护一份映射关系，把过敏原展开成食材 ID 集合，
 * 再用集合做求交。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_allergen_ingredient")
public class AllergenIngredient extends BaseEntity {

    /** 过敏原编码，如 PEANUT。 */
    private String allergenCode;
    private Long ingredientId;
    /** EXACT 直接命中 / DERIVED 衍生制品（花生油、花生酱）/ TRACE 可能含痕量。 */
    private String relationType;
}
