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
