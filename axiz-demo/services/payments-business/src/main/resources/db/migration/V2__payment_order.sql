-- Payment orders. Account numbers are stored encrypted (axz1:<key>:<data>); the creditor account
-- also has a blind index to find duplicates without decrypting.
CREATE TABLE payment_order (
  id                 UUID          PRIMARY KEY,
  version            BIGINT,
  operation_number   VARCHAR(40)   NOT NULL UNIQUE,
  customer_id        VARCHAR(20)   NOT NULL,
  debtor_account     VARCHAR(200)  NOT NULL,
  creditor_cci       VARCHAR(200)  NOT NULL,
  creditor_cci_index VARCHAR(64)   NOT NULL,
  amount             NUMERIC(18,2) NOT NULL CHECK (amount > 0),
  currency           CHAR(3)       NOT NULL,
  description        VARCHAR(140),
  processing_date    DATE          NOT NULL,
  status             VARCHAR(16)   NOT NULL,
  status_reason      VARCHAR(200),
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
  created_by         VARCHAR(100),
  updated_at         TIMESTAMP WITH TIME ZONE,
  updated_by         VARCHAR(100)
);
CREATE INDEX payment_order_customer ON payment_order (customer_id, created_at DESC);
CREATE INDEX payment_order_day ON payment_order (customer_id, currency, processing_date);
CREATE INDEX payment_order_duplicates ON payment_order (customer_id, creditor_cci_index, processing_date);
