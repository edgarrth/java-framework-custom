-- payment-process: initial schema (Aurora PostgreSQL). Flyway owns the schema in every
-- environment; Hibernate only validates it (spring.jpa.hibernate.ddl-auto=validate).
-- Add domain tables in new migrations (V2__..., never edit an applied one).

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

-- Tables of the scheduling capability (added by axiz add)

-- ShedLock table (one row per scheduled task); the lock time comes from the database clock.
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
