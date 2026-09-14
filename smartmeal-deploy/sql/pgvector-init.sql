-- =============================================================
--  SmartMeal 向量库初始化（PostgreSQL + pgvector）
--
--  用途：存放 RAG 知识库的向量索引。
--  当前状态：**未启用**。本机没有部署 PostgreSQL，检索走
--  InMemoryRetrievalService（直接查 MySQL 做关键词打分）。
--
--  什么时候需要它：
--    知识库规模超过约 5000 条切片后，关键词匹配的召回质量会明显下降，
--    此时需要切换到语义检索。切换步骤见 README「接入 pgvector」一节。
--
--  为什么单独用 PostgreSQL 而不是在 MySQL 里做：
--    MySQL 8 没有原生向量类型与 HNSW 索引，用 JSON 存向量再全表算余弦
--    在 10 万条量级下会直接打满 CPU。pgvector 的 HNSW 索引是当前最省事的方案。
-- =============================================================

CREATE DATABASE smartmeal_vector;

\c smartmeal_vector

CREATE EXTENSION IF NOT EXISTS vector;

-- 知识切片表
-- 注意：document_id 对应 MySQL 的 t_knowledge_document.id。
-- 跨库无法建外键，一致性靠应用层保证（同步任务负责）。
CREATE TABLE IF NOT EXISTS t_knowledge_embedding (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT       NOT NULL,
    chunk_no    INT          NOT NULL,
    source_type VARCHAR(32),
    source_id   BIGINT,
    title       VARCHAR(255),
    content     TEXT         NOT NULL,
    -- 1024 维：text-embedding-v3 / bge-m3 的默认维度。
    -- 换模型时这个维度必须同步修改，否则插入直接报错。
    embedding   vector(1024),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, chunk_no)
);

-- HNSW 索引：查询快、构建慢，适合读多写少的场景。
-- 对比 IVFFlat：IVFFlat 需要预先知道数据分布并调 lists 参数，
-- 且新增数据后需要重建才能保持精度；HNSW 增量插入友好，更省心。
CREATE INDEX IF NOT EXISTS idx_embedding_hnsw
    ON t_knowledge_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_embedding_document ON t_knowledge_embedding (document_id);
CREATE INDEX IF NOT EXISTS idx_embedding_source ON t_knowledge_embedding (source_type, source_id);

-- 检索示例（应用层通过 Spring AI 的 PgVectorStore 调用，这里仅作参考）：
-- SELECT source_id, title, content, 1 - (embedding <=> :queryVector) AS score
-- FROM t_knowledge_embedding
-- WHERE source_type = 'RECIPE'
-- ORDER BY embedding <=> :queryVector
-- LIMIT 10;
