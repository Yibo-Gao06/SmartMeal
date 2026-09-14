package com.smartmeal.ai.rag;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条召回的知译片段。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    /** 全局唯一来源 ID，形如 RECIPE_5001 / INGREDIENT_1001，会写进模型的 sourceIds 便于溯源。 */
    private String sourceId;

    /** RECIPE / INGREDIENT / NUTRITION / GUIDE。 */
    private String type;

    private String title;
    private String content;

    /** 相似度得分，越高越相关。 */
    private double score;

    /** 关联的食材 ID，用于过敏原过滤。 */
    private Long ingredientId;

    public KnowledgeChunk(String sourceId, String type, String title, String content, double score) {
        this.sourceId = sourceId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.score = score;
    }
}
