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
