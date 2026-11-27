/**
 * Autorización a nivel de objeto de los comandos y consultas (quién puede actuar sobre qué cliente
 * y qué orden). Las políticas se ejecutan en el pipeline CQRS previo a la idempotencia, las
 * transacciones y los handlers.
 */
@NullMarked
package pe.axiz.payments.orders.application.access;

import org.jspecify.annotations.NullMarked;
