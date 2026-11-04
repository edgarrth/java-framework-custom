-- Daily limit reservations: one row per customer, currency and processing date. Orders reserve
-- with an atomic conditional upsert (the row stays locked until commit), so concurrent orders of
-- the same customer and day are serialized and can never exceed the limit together.
CREATE TABLE payment_daily_usage (
  customer_id     VARCHAR(20)   NOT NULL,
  currency        CHAR(3)       NOT NULL,
  processing_date DATE          NOT NULL,
  reserved        NUMERIC(18,2) NOT NULL CHECK (reserved >= 0),
  PRIMARY KEY (customer_id, currency, processing_date)
);

-- Existing orders keep counting against their day (upgrade without losing the limit state).
INSERT INTO payment_daily_usage (customer_id, currency, processing_date, reserved)
SELECT customer_id, currency, processing_date, SUM(amount)
  FROM payment_order
 WHERE status <> 'REJECTED'
 GROUP BY customer_id, currency, processing_date;

-- The client's key of the request that created the order (its Idempotency-Key): a retried request
-- returns the same order. Orders created before this migration have none.
ALTER TABLE payment_order ADD COLUMN request_key VARCHAR(64);
CREATE UNIQUE INDEX payment_order_request_key ON payment_order (customer_id, request_key)
  WHERE request_key IS NOT NULL;
