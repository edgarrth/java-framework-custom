-- payments-history: initial schema (Aurora PostgreSQL). Flyway owns the schema in every
-- environment: it runs over JDBC at startup, the service itself only uses R2DBC.
-- Add domain tables in new migrations (V2__..., never edit an applied one).

-- axiz inbox (Aurora PostgreSQL). Include it in the service Flyway migrations.
CREATE TABLE IF NOT EXISTS axiz_inbox (
  consumer     VARCHAR(100) NOT NULL,
  message_id   VARCHAR(128) NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  received_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  lease_until  TIMESTAMP WITH TIME ZONE NOT NULL,
  processed_at TIMESTAMP WITH TIME ZONE,
  expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (consumer, message_id)
);
CREATE INDEX IF NOT EXISTS axiz_inbox_expires ON axiz_inbox (expires_at);
