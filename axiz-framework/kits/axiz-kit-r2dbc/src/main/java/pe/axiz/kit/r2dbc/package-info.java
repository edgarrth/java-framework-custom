/**
 * Non-blocking access to Aurora PostgreSQL over R2DBC for layer 1 stores (outbox, inbox) and
 * services: {@link pe.axiz.kit.r2dbc.ReactiveSql} with named parameters, connection scoped
 * transactions and IAM authentication tokens. Types are non-null unless annotated
 * {@code @Nullable}.
 */
@NullMarked
package pe.axiz.kit.r2dbc;

import org.jspecify.annotations.NullMarked;
