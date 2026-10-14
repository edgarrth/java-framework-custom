package pe.axiz.payments.settlement.job;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;
import pe.axiz.core.identifiers.IdentifierRegistry;

/**
 * Normalizes settlement lines and filters those whose destination account fails the CCI check
 * digits (counted as filtered in the step metrics; the network should never send them).
 */
@Component
public class SettlementLineProcessor implements ItemProcessor<SettlementLine, SettlementLine> {

  private static final Logger log = LoggerFactory.getLogger(SettlementLineProcessor.class);

  private final IdentifierRegistry identifiers;

  public SettlementLineProcessor(IdentifierRegistry identifiers) {
    this.identifiers = identifiers;
  }

  @Override
  public @Nullable SettlementLine process(SettlementLine line) {
    if (!identifiers.isValid("cci-strict", line.creditorCci())) {
      log.warn("Settlement line {} filtered: invalid destination account", line.endToEndId());
      return null;
    }
    return new SettlementLine(
        line.endToEndId(),
        line.transferId(),
        line.creditorCci(),
        line.amount(),
        line.currency().toUpperCase(Locale.ROOT),
        line.status(),
        line.settledAt());
  }
}
