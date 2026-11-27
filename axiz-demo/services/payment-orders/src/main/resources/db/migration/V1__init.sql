-- payment-orders: esquema inicial (Aurora PostgreSQL).

-- Outbox de axiz (Aurora PostgreSQL), incluido en las migraciones Flyway del servicio.
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

-- Secuencias de axiz (Aurora PostgreSQL)
CREATE TABLE IF NOT EXISTS axiz_sequence (
  seq_name   VARCHAR(64) NOT NULL,
  seq_period VARCHAR(16) NOT NULL,
  seq_value  BIGINT      NOT NULL,
  PRIMARY KEY (seq_name, seq_period)
);

-- Órdenes de pago. Los números de cuenta se guardan cifrados (axz1:<key>:<data>); la cuenta del
-- acreedor tiene además un índice ciego para encontrar duplicados sin descifrar.
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

-- Reservas del límite diario: una fila por cliente, moneda y fecha de proceso. Las órdenes reservan
-- con un upsert condicional atómico (la fila queda bloqueada hasta el commit), de modo que las
-- órdenes concurrentes del mismo cliente y día se serializan y nunca superan juntas el límite.
CREATE TABLE payment_daily_usage (
  customer_id     VARCHAR(20)   NOT NULL,
  currency        VARCHAR(3)    NOT NULL CONSTRAINT payment_daily_usage_currency_iso CHECK (currency ~ '^[A-Z]{3}$'),
  processing_date DATE          NOT NULL,
  reserved        NUMERIC(18,2) NOT NULL CHECK (reserved >= 0),
  PRIMARY KEY (customer_id, currency, processing_date)
);


CREATE UNIQUE INDEX payment_order_request_key ON payment_order (customer_id, request_key)
  WHERE request_key IS NOT NULL;

-- Sello de auditoría durable: se confirma en la misma transacción que la orden y sus registros de outbox.
CREATE TABLE IF NOT EXISTS axiz_audit_chain (
    chain_id VARCHAR(256) PRIMARY KEY,
    last_sequence BIGINT NOT NULL CHECK (last_sequence >= 0),
    last_hash VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
