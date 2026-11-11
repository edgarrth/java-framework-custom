-- payment-orders: initial schema (Aurora PostgreSQL). Flyway owns the schema in every
-- environment; Hibernate only validates it (spring.jpa.hibernate.ddl-auto=validate).
-- Add domain tables in new migrations (V2__..., never edit an applied one).

-- axiz outbox (Aurora PostgreSQL). Include it in the service Flyway migrations.
CREATE TABLE IF NOT EXISTS axiz_outbox (
  id              VARCHAR(64)  PRIMARY KEY,
  destination     VARCHAR(512) NOT NULL,
  aggregate_type  VARCHAR(100),
  aggregate_id    VARCHAR(200),
  event_type      VARCHAR(200) NOT NULL,
  envelope        TEXT         NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  attempts        INTEGER      NOT NULL DEFAULT 0,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at    TIMESTAMP WITH TIME ZONE,
  last_error      VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS axiz_outbox_due ON axiz_outbox (status, next_attempt_at, created_at);

-- axiz sequences (Aurora PostgreSQL)
CREATE TABLE IF NOT EXISTS axiz_sequence (
  seq_name   VARCHAR(64) NOT NULL,
  seq_period VARCHAR(16) NOT NULL,
  seq_value  BIGINT      NOT NULL,
  PRIMARY KEY (seq_name, seq_period)
);
