-- Read-only diagnosis of the current v2 schema. Never changes event status.
WITH events AS (
  SELECT id, status, attempts, last_error, destination, created_at, aggregate_seq,
    aggregate_type AS kind, aggregate_id AS aggregate
  FROM axiz_outbox
)
SELECT f.id, f.kind AS aggregate_type, f.aggregate AS aggregate_id,
  f.destination, f.attempts, f.last_error,
  EXISTS (SELECT 1 FROM events p WHERE p.kind=f.kind AND p.aggregate=f.aggregate
    AND p.aggregate_seq>f.aggregate_seq AND p.status='PUBLISHED') AS published_successors,
  EXISTS (SELECT 1 FROM events p WHERE p.kind=f.kind AND p.aggregate=f.aggregate
    AND p.aggregate_seq>f.aggregate_seq AND p.status IN ('PENDING','IN_FLIGHT')) AS pending_successors
FROM events f WHERE f.status='FAILED' ORDER BY f.kind, f.aggregate, f.aggregate_seq;
