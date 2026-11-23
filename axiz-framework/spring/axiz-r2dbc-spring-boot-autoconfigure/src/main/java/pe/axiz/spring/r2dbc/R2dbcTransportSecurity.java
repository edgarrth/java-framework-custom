package pe.axiz.spring.r2dbc;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.util.Locale;
import org.springframework.boot.r2dbc.OptionsCapableConnectionFactory;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

/**
 * Validación explícita del transporte para las fábricas construidas y las opciones finales de las
 * fábricas personalizadas.
 */
public final class R2dbcTransportSecurity {
  private R2dbcTransportSecurity() {}

  /**
   * Usa las opciones finales que conserva Boot, después de que se hayan ejecutado todos los
   * customizers de opciones.
   */
  public static void validate(ConnectionFactory factory, DataTransportSecurityPolicy policy) {
    var configured = OptionsCapableConnectionFactory.unwrapFrom(factory);
    if (configured == null) {
      policy.requireVerified(false, false, "Custom R2DBC factory with no verifiable options");
      return;
    }
    validateOptions(configured.getOptions(), policy);
  }

  /**
   * Las fábricas personalizadas deben invocar esto antes de que un constructor o un
   * precalentamiento pueda realizar E/S.
   */
  public static void validateOptions(
      ConnectionFactoryOptions options, DataTransportSecurityPolicy policy) {
    Object driver = options.getValue(ConnectionFactoryOptions.DRIVER);
    if ("pool".equals(driver)) driver = options.getValue(ConnectionFactoryOptions.PROTOCOL);
    Object protocol = options.getValue(ConnectionFactoryOptions.PROTOCOL);
    boolean embedded =
        "h2".equals(driver)
            && options.getValue(ConnectionFactoryOptions.HOST) == null
            && ("mem".equals(protocol) || "file".equals(protocol));
    if (embedded) {
      Environments.requireSingleProcess(
          policy.environment(),
          "embedded R2DBC database",
          "shared database",
          "embedded state is local to one process");
      return;
    }
    if (!"postgresql".equals(driver)) {
      policy.requireVerified(false, false, "R2DBC driver without an effective TLS validator");
      return;
    }
    Object mode = options.getValue(Option.valueOf("sslMode"));
    boolean verified =
        mode != null
            ? mode.toString().replace('_', '-').toLowerCase(Locale.ROOT).equals("verify-full")
            : Boolean.TRUE.equals(options.getValue(ConnectionFactoryOptions.SSL));
    // Callbacks arbitrarios pueden reemplazar el material de confianza u omitir las comprobaciones
    // del hostname después de esta guarda.
    for (String hook :
        new String[] {
          "sslHostnameVerifier",
          "sslContextBuilderCustomizer",
          "sslEngineCustomizer",
          "sslParametersFactory"
        }) {
      if (options.getValue(Option.valueOf(hook)) != null) verified = false;
    }
    policy.requireVerified(verified, verified, "PostgreSQL R2DBC");
  }
}
