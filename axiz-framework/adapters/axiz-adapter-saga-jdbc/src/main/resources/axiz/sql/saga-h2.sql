-- axiz saga state (Aurora PostgreSQL)
CREATE TABLE IF NOT EXISTS axiz_saga (
  saga_id    VARCHAR(64)  PRIMARY KEY,
  saga_name  VARCHAR(100) NOT NULL,
  status     VARCHAR(16)  NOT NULL,
  step_index INTEGER      NOT NULL,
  context    TEXT         NOT NULL,
  error      VARCHAR(1000),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  version    BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS axiz_saga_stale ON axiz_saga (status, updated_at);
