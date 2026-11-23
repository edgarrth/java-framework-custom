-- Una base de datos por cada servicio que posee datos en Aurora (cada servicio tiene su propio esquema y Flyway).
CREATE DATABASE payment_orders;
CREATE DATABASE payment_process;
CREATE DATABASE payments_history;
CREATE DATABASE settlement_batch;
