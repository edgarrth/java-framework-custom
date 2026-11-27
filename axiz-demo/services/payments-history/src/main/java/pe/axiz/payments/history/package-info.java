/**
 * Payments History: historial de pagos de cada cliente: modelo de lectura proyectado a partir de
 * los eventos de órdenes de pago sobre R2DBC. Arquetipo business, estructura hexagonal con
 * extensión CQRS, stack reactive. Los tipos no son nulos salvo que se anoten con {@code @Nullable}.
 */
@NullMarked
package pe.axiz.payments.history;

import org.jspecify.annotations.NullMarked;
