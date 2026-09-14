package com.smartmeal.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartmeal.domain.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

/** t_knowledge_document 知识库文档 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
