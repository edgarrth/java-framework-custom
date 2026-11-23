-- secuencias de axiz (Aurora PostgreSQL)
CREATE TABLE IF NOT EXISTS axiz_sequence (
  seq_name   VARCHAR(64) NOT NULL,
  seq_period VARCHAR(16) NOT NULL,
  seq_value  BIGINT      NOT NULL,
  PRIMARY KEY (seq_name, seq_period)
);
