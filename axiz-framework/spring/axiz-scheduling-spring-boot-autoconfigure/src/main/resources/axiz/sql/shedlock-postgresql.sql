-- ShedLock table (one row per scheduled task); the lock time comes from the database clock.
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
