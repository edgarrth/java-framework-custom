-- H2 local/test upgrade. H2 commits DDL implicitly: back up before running.
-- Stop every writer and relay for the migration.
ALTER TABLE axiz_outbox
  ADD COLUMN aggregate_seq BIGINT;
ALTER TABLE axiz_outbox
  ADD COLUMN lease_owner VARCHAR(64);
ALTER TABLE axiz_outbox
  ADD COLUMN lease_until   TIMESTAMP WITH TIME ZONE;

-- Conservar pares completos; sin identidad completa, cada evento se ordena solo.
UPDATE axiz_outbox
SET aggregate_type = 'axiz.event', aggregate_id = id
WHERE aggregate_type IS NULL OR aggregate_id IS NULL;

-- Orden determinista del historial disponible, antes de exigir NOT NULL/UNIQUE.
MERGE INTO axiz_outbox o USING (
  SELECT id, ROW_NUMBER() OVER (
    PARTITION BY aggregate_type, aggregate_id ORDER BY created_at, id
  ) AS seq FROM axiz_outbox
) ordered ON (o.id = ordered.id)
WHEN MATCHED THEN UPDATE SET o.aggregate_seq = ordered.seq;

CREATE TABLE axiz_outbox_aggregate (
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id   VARCHAR(200) NOT NULL,
  last_seq       BIGINT       NOT NULL,
  PRIMARY KEY (aggregate_type, aggregate_id));

INSERT INTO axiz_outbox_aggregate (aggregate_type, aggregate_id, last_seq)
SELECT aggregate_type, aggregate_id, MAX(aggregate_seq)
FROM axiz_outbox GROUP BY aggregate_type, aggregate_id;

-- Las filas FAILED de 1.x se conservan tal cual (estado, error e intentos): el upgrade no decide
-- su destino. El reporte de §3.2 («FAILED heredados») las lista antes y después del upgrade.

ALTER TABLE axiz_outbox
  ALTER COLUMN aggregate_type SET NOT NULL;
ALTER TABLE axiz_outbox
  ALTER COLUMN aggregate_id SET NOT NULL;
ALTER TABLE axiz_outbox
  ALTER COLUMN aggregate_seq  SET NOT NULL;

-- status: PENDING | IN_FLIGHT | PUBLISHED | FAILED | SKIPPED
CREATE UNIQUE INDEX axiz_outbox_aggregate_seq ON axiz_outbox (aggregate_type, aggregate_id, aggregate_seq);
CREATE INDEX axiz_outbox_open ON axiz_outbox (aggregate_type, aggregate_id, aggregate_seq);

-- Post-upgrade report: operation decisions remain explicit.
-- Read-only diagnostic, compatible with the pre-upgrade columns. Never changes event status.
WITH events AS (
  SELECT id, status, attempts, last_error, destination, created_at,
    CASE WHEN aggregate_type IS NULL OR aggregate_id IS NULL THEN 'axiz.event' ELSE aggregate_type END AS kind,
    CASE WHEN aggregate_type IS NULL OR aggregate_id IS NULL THEN id ELSE aggregate_id END AS aggregate
  FROM axiz_outbox
)
SELECT f.id, f.kind AS aggregate_type, f.aggregate AS aggregate_id,
  f.destination, f.attempts, f.last_error,
  EXISTS (SELECT 1 FROM events p WHERE p.kind=f.kind AND p.aggregate=f.aggregate
    AND (p.created_at>f.created_at OR (p.created_at=f.created_at AND p.id>f.id)) AND p.status='PUBLISHED') AS published_successors,
  EXISTS (SELECT 1 FROM events p WHERE p.kind=f.kind AND p.aggregate=f.aggregate
    AND (p.created_at>f.created_at OR (p.created_at=f.created_at AND p.id>f.id)) AND p.status='PENDING') AS pending_successors
FROM events f WHERE f.status='FAILED' ORDER BY f.kind, f.aggregate, f.created_at, f.id;
