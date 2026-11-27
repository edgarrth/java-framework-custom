-- payments-history: esquema inicial (Aurora PostgreSQL). Flyway es dueño del esquema en todos los
-- ambientes: se ejecuta sobre JDBC al arrancar y el servicio solo usa R2DBC.
-- Las tablas de dominio se agregan en migraciones nuevas (V2__...); una migración aplicada nunca se edita.

-- Inbox de axiz (Aurora PostgreSQL), incluido en las migraciones Flyway del servicio.
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

-- Modelo de lectura de los pagos de cada cliente, proyectado desde los eventos de las órdenes de pago.
-- Las columnas corresponden a PaymentHistoryRow (Spring Data R2DBC, nombres en snake_case).
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

-- GET /v1/customers/{customerId}/payments (opcionalmente por estado), del cambio más reciente al más antiguo
CREATE INDEX payment_history_customer ON payment_history (customer_id, last_change_at DESC);
CREATE INDEX payment_history_customer_status ON payment_history (customer_id, status, last_change_at DESC);
