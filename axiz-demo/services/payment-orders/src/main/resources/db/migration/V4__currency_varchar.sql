-- Currency codes as VARCHAR(3) with a format check, instead of CHAR(3). The entity maps them as
-- strings of length 3, which Hibernate validates as VARCHAR (ddl-auto=validate rejects a bpchar
-- column), and CHAR(n) only adds blank padding in PostgreSQL. ISO 4217 codes are three upper-case
-- letters.
ALTER TABLE payment_order
  ALTER COLUMN currency TYPE VARCHAR(3),
  ADD CONSTRAINT payment_order_currency_iso CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE payment_daily_usage
  ALTER COLUMN currency TYPE VARCHAR(3),
  ADD CONSTRAINT payment_daily_usage_currency_iso CHECK (currency ~ '^[A-Z]{3}$');
