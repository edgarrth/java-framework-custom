/**
 * Acceso no bloqueante a Aurora PostgreSQL mediante R2DBC para los almacenes de la capa 1 (outbox,
 * inbox) y los servicios: {@link pe.axiz.kit.r2dbc.ReactiveSql} con parámetros con nombre,
 * transacciones con alcance de conexión y tokens de autenticación IAM. Los tipos no admiten null
 * salvo que se anoten con {@code @Nullable}.
 */
@NullMarked
package pe.axiz.kit.r2dbc;

import org.jspecify.annotations.NullMarked;
