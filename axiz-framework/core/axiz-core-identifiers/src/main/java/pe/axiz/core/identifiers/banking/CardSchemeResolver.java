package pe.axiz.core.identifiers.banking;

import java.util.Optional;
import pe.axiz.core.identifiers.banking.BankingIdentifiers.CardBrand;

/**
 * Resuelve la marca de un PAN (o de sus dígitos iniciales) a partir de una fuente autoritativa: la
 * tabla IIN/BIN que la organización recibe de las marcas o de su procesador, versionada y
 * reemplazable sin desplegar código. Se usa para decisiones de enrutamiento, autorización,
 * aceptación, precios y fraude; {@link CardBrand#guess} es solo una pista de presentación.
 */
public interface CardSchemeResolver {

  /**
   * Marca de la tarjeta.
   *
   * @param pan número de tarjeta (o al menos sus primeros 6 a 8 dígitos); solo dígitos
   * @return la marca, vacío cuando la tabla no tiene un rango para ella (se trata como no aceptada)
   */
  Optional<CardBrand> resolve(String pan);

  /** Origen de los rangos (nombre, versión y fecha de la tabla), para auditoría y logs. */
  String source();
}
