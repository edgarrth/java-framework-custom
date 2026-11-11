/**
 * Payments History: Payment history of each customer: read model projected from the payment order
 * events over R2DBC. Archetype business, hexagonal structure with CQRS extension, reactive stack.
 * Types are non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package pe.axiz.payments.history;

import org.jspecify.annotations.NullMarked;
