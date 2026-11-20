-- The durable audit seal and the order/outbox records use the same transaction.
-- Keep V1 immutable for existing installations; apply this before accepting commands.
CREATE TABLE IF NOT EXISTS axiz_audit_chain (
    chain_id VARCHAR(256) PRIMARY KEY,
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 0),
    last_hash VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
