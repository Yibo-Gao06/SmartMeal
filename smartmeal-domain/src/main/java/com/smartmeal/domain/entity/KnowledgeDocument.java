package com.smartmeal.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库文档 t_knowledge_document，RAG 的原始语料。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    /** RECIPE / INGREDIENT / NUTRITION / GUIDE。 */
    private String sourceType;
    private Long sourceId;
    private String title;
    private String content;
    /** 附加元信息 JSON，如 {"goal":"loss_fat","cookTime":15}，用于检索时结构化过滤。 */
    private String metadata;
    private Integer version;
    private Integer status;
}
