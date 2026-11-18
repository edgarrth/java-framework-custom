-- Initial schema for the first public Axiz 2.0 distribution.

-- axiz outbox (Aurora PostgreSQL). Include it in the service Flyway migrations.
CREATE TABLE IF NOT EXISTS axiz_outbox (
  id              VARCHAR(64)  PRIMARY KEY,
  destination     VARCHAR(512) NOT NULL,
  aggregate_type  VARCHAR(100) NOT NULL,
  aggregate_id    VARCHAR(200) NOT NULL,
  event_type      VARCHAR(200) NOT NULL,
  envelope        TEXT         NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  attempts        INTEGER      NOT NULL DEFAULT 0,
  created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
  published_at    TIMESTAMP WITH TIME ZONE,
  last_error      VARCHAR(1000),
  aggregate_seq   BIGINT NOT NULL,
  lease_owner     VARCHAR(64),
  lease_until     TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS axiz_outbox_due ON axiz_outbox (status, next_attempt_at, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS axiz_outbox_aggregate_seq ON axiz_outbox (aggregate_type, aggregate_id, aggregate_seq);
CREATE TABLE IF NOT EXISTS axiz_outbox_aggregate (
  aggregate_type VARCHAR(100) NOT NULL,
  aggregate_id VARCHAR(200) NOT NULL,
  last_seq BIGINT NOT NULL,
  PRIMARY KEY (aggregate_type, aggregate_id)
);

CREATE INDEX IF NOT EXISTS axiz_outbox_open ON axiz_outbox (aggregate_type, aggregate_id, aggregate_seq)
  WHERE status NOT IN ('PUBLISHED', 'SKIPPED');

-- axiz sequences (Aurora PostgreSQL)
CREATE TABLE IF NOT EXISTS axiz_sequence (
  seq_name   VARCHAR(64) NOT NULL,
  seq_period VARCHAR(16) NOT NULL,
  seq_value  BIGINT      NOT NULL,
  PRIMARY KEY (seq_name, seq_period)
);

-- Payment orders. Account numbers are stored encrypted (axz1:<key>:<data>); the creditor account
-- also has a blind index to find duplicates without decrypting.
CREATE TABLE payment_order (
  id                 UUID          PRIMARY KEY,
  version            BIGINT,
  operation_number   VARCHAR(40)   NOT NULL UNIQUE,
  customer_id        VARCHAR(20)   NOT NULL,
  debtor_account     VARCHAR(200)  NOT NULL,
  creditor_cci       VARCHAR(200)  NOT NULL,
  creditor_cci_index VARCHAR(256)   NOT NULL,
  amount             NUMERIC(18,2) NOT NULL CHECK (amount > 0),
  currency           VARCHAR(3)    NOT NULL CONSTRAINT payment_order_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
  description        VARCHAR(140),
  processing_date    DATE          NOT NULL,
  status             VARCHAR(16)   NOT NULL,
  status_reason      VARCHAR(200),
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by         VARCHAR(100),
  updated_at         TIMESTAMP WITH TIME ZONE,
  updated_by         VARCHAR(100),
  request_key        VARCHAR(64)
);
CREATE INDEX payment_order_customer ON payment_order (customer_id, created_at DESC);
CREATE INDEX payment_order_day ON payment_order (customer_id, currency, processing_date);
CREATE INDEX payment_order_duplicates ON payment_order (customer_id, creditor_cci_index, processing_date);

-- Daily limit reservations: one row per customer, currency and processing date. Orders reserve
-- with an atomic conditional upsert (the row stays locked until commit), so concurrent orders of
-- the same customer and day are serialized and can never exceed the limit together.
CREATE TABLE payment_daily_usage (
  customer_id     VARCHAR(20)   NOT NULL,
  currency        VARCHAR(3)    NOT NULL CONSTRAINT payment_daily_usage_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
  processing_date DATE          NOT NULL,
  reserved        NUMERIC(18,2) NOT NULL CHECK (reserved >= 0),
  PRIMARY KEY (customer_id, currency, processing_date)
);


CREATE UNIQUE INDEX payment_order_request_key ON payment_order (customer_id, request_key)
  WHERE request_key IS NOT NULL;
