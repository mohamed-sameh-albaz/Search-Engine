-- Add TF and importance columns to inverted_index table
ALTER TABLE inverted_index ADD COLUMN IF NOT EXISTS tf DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE inverted_index ADD COLUMN IF NOT EXISTS importance INT DEFAULT 1;

-- Create word_position table for phrase processing
CREATE TABLE IF NOT EXISTS word_position (
    id BIGSERIAL PRIMARY KEY,
    word_id BIGINT NOT NULL REFERENCES words(id),
    doc_id BIGINT NOT NULL REFERENCES documents(id),
    position INT NOT NULL,
    tag VARCHAR(10),
    CONSTRAINT word_position_word_doc_pos_unique UNIQUE (word_id, doc_id, position)
);

-- Create indexes for word_position table
CREATE INDEX IF NOT EXISTS idx_word_position_word_id ON word_position (word_id);
CREATE INDEX IF NOT EXISTS idx_word_position_doc_id ON word_position (doc_id);
CREATE INDEX IF NOT EXISTS idx_word_position_position ON word_position (position);
CREATE INDEX IF NOT EXISTS idx_word_position_word_doc ON word_position (word_id, doc_id);

-- Modify document id to auto increment
-- This is already set up, but adding a comment for clarity
COMMENT ON COLUMN documents.id IS 'Auto-incremented primary key'; 