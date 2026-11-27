package pe.axiz.payments.settlement.job;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;
import pe.axiz.core.identifiers.IdentifierRegistry;

/**
 * Normaliza las líneas de liquidación y filtra aquellas cuya cuenta de destino no supera los
 * dígitos de control del CCI (se cuentan como filtradas en las métricas del paso; la red nunca
 * debería enviarlas).
 */
@Component
public class SettlementLineProcessor implements ItemProcessor<SettlementLine, SettlementLine> {

  private static final Logger LOG = LoggerFactory.getLogger(SettlementLineProcessor.class);

  private final IdentifierRegistry identifiers;

  public SettlementLineProcessor(IdentifierRegistry identifiers) {
    this.identifiers = identifiers;
  }

  @Override
  public @Nullable SettlementLine process(SettlementLine line) {
    if (!identifiers.isValid("cci-strict", line.creditorCci())) {
      LOG.warn("Settlement line {} filtered: invalid destination account", line.endToEndId());
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
