package pe.axiz.payments.bankingcore.adapter.out.corebanking.model;

import org.jspecify.annotations.Nullable;

/**
 * CoreDebit.
 *
 * @param amt monto decimal como texto
 * @param ccy ccy
 * @param ref ref
 * @param holder cliente que debe ser titular de la cuenta; en caso contrario, el core rechaza el
 *     débito (CB-17)
 */
public record CoreDebit(String amt, String ccy, String ref, @Nullable String holder) {}
