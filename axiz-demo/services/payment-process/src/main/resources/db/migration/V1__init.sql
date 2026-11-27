-- payment-process: esquema inicial (Aurora PostgreSQL). Flyway es dueño del esquema en todos los
-- ambientes; Hibernate solo lo valida (spring.jpa.hibernate.ddl-auto=validate).
-- Las tablas de dominio se agregan en migraciones nuevas (V2__...); una migración aplicada nunca se edita.

-- Estado de sagas de axiz (Aurora PostgreSQL)
CREATE TABLE IF NOT EXISTS axiz_saga (
  saga_id    VARCHAR(64)  PRIMARY KEY,
  saga_name  VARCHAR(100) NOT NULL,
  status     VARCHAR(16)  NOT NULL,
  step_index INTEGER      NOT NULL,
  context    TEXT         NOT NULL,
  error      VARCHAR(1000),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  version    BIGINT       NOT NULL,
  execution_owner VARCHAR(36),
  execution_until TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS axiz_saga_stale ON axiz_saga (status, updated_at);

-- Tablas de la capacidad scheduling (agregadas por axiz add)

-- Tabla de ShedLock (una fila por tarea programada); la hora del lock proviene del reloj de la base.
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
