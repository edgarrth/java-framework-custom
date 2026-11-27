-- Tabla de ShedLock (una fila por tarea programada); el tiempo del lock proviene del reloj de la base de datos.
CREATE TABLE IF NOT EXISTS shedlock (
  name       VARCHAR(64)  NOT NULL PRIMARY KEY,
  lock_until TIMESTAMPTZ  NOT NULL,
  locked_at  TIMESTAMPTZ  NOT NULL,
  locked_by  VARCHAR(255) NOT NULL
);
