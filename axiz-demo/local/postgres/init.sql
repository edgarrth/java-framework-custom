-- One database per service that owns data in Aurora (each service has its own schema and Flyway).
CREATE DATABASE payment_orders;
CREATE DATABASE payment_process;
CREATE DATABASE payments_history;
CREATE DATABASE settlement_batch;
