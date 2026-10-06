-- Optimistic locking (@Version): concurrent updates to the same row are detected instead of
-- one silently overwriting the other. Existing rows start at version 0.
ALTER TABLE agents ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE reassignment_suggestions ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
