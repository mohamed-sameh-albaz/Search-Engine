-- Add word_idf table to store IDF values for each word
CREATE TABLE IF NOT EXISTS word_idf (
    id BIGSERIAL PRIMARY KEY,
    word_id BIGINT NOT NULL REFERENCES words(id),
    idf_value DOUBLE PRECISION NOT NULL,
    document_frequency BIGINT NOT NULL,
    total_documents BIGINT NOT NULL,
    CONSTRAINT word_idf_word_id_unique UNIQUE (word_id)
);

-- Create index on word_id in word_idf table
CREATE INDEX IF NOT EXISTS idx_word_idf_word_id ON word_idf (word_id);

-- Add word_document_metrics table to store precomputed TF-IDF scores
CREATE TABLE IF NOT EXISTS word_document_metrics (
    id BIGSERIAL PRIMARY KEY,
    word_id BIGINT NOT NULL REFERENCES words(id),
    doc_id BIGINT NOT NULL REFERENCES documents(id),
    frequency INT NOT NULL,
    term_frequency DOUBLE PRECISION NOT NULL,
    tf_idf_score DOUBLE PRECISION NOT NULL,
    normalized_score DOUBLE PRECISION NOT NULL,
    CONSTRAINT metrics_word_id_doc_id_unique UNIQUE (word_id, doc_id)
);

-- Create indexes for word_document_metrics
CREATE INDEX IF NOT EXISTS idx_metrics_word_id ON word_document_metrics (word_id);
CREATE INDEX IF NOT EXISTS idx_metrics_doc_id ON word_document_metrics (doc_id);
CREATE INDEX IF NOT EXISTS idx_metrics_word_doc ON word_document_metrics (word_id, doc_id);
CREATE INDEX IF NOT EXISTS idx_metrics_tf_idf_score ON word_document_metrics (tf_idf_score DESC);

-- Add index on normalized_score for faster ranking retrieval
CREATE INDEX IF NOT EXISTS idx_metrics_normalized_score ON word_document_metrics (normalized_score DESC); 