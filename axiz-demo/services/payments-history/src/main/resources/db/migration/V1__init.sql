-- payments-history: initial schema (Aurora PostgreSQL). Flyway owns the schema in every
-- environment: it runs over JDBC at startup, the service itself only uses R2DBC.
-- Add domain tables in new migrations (V2__..., never edit an applied one).

-- axiz inbox (Aurora PostgreSQL). Include it in the service Flyway migrations.
CREATE TABLE IF NOT EXISTS axiz_inbox (
  consumer     VARCHAR(100) NOT NULL,
  message_id   VARCHAR(128) NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  received_at  TIMESTAMP WITH TIME ZONE NOT NULL,
  lease_until  TIMESTAMP WITH TIME ZONE NOT NULL,
  processed_at TIMESTAMP WITH TIME ZONE,
  expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  owner        VARCHAR(64),
  PRIMARY KEY (consumer, message_id)
);
CREATE INDEX IF NOT EXISTS axiz_inbox_expires ON axiz_inbox (expires_at);

-- Read model of the payments of each customer, projected from the payment order events.
-- Columns map to PaymentHistoryRow (Spring Data R2DBC, snake_case names).
CREATE TABLE payment_history (
  id               UUID         PRIMARY KEY,
  version          BIGINT       NOT NULL,
  payment_id       VARCHAR(64)  NOT NULL,
  customer_id      VARCHAR(20)  NOT NULL,
  operation_number VARCHAR(40),
  creditor_cci     VARCHAR(200),             -- encrypted (EncryptedText)
  amount           VARCHAR(40),              -- Money text form, e.g. "PEN 150.75"
  processing_date  DATE,
  status           VARCHAR(16)  NOT NULL,
  reason           VARCHAR(200),
  last_change_at   TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by       VARCHAR(100) NOT NULL,
  updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_by       VARCHAR(100) NOT NULL,
  CONSTRAINT payment_history_payment UNIQUE (payment_id)
);

-- GET /v1/customers/{customerId}/payments (optionally by status), latest change first
CREATE INDEX payment_history_customer ON payment_history (customer_id, last_change_at DESC);
CREATE INDEX payment_history_customer_status ON payment_history (customer_id, status, last_change_at DESC);
