package pe.axiz.payments.coreintegration.adapter.out.corebanking.model;

import org.jspecify.annotations.Nullable;

/**
 * CoreDebit.
 *
 * @param amt decimal amount as text
 * @param ccy ccy
 * @param ref ref
 * @param holder customer who must own the account; the core rejects the debit otherwise (CB-17)
 */
public record CoreDebit(String amt, String ccy, String ref, @Nullable String holder) {}
