package pe.axiz.spring.r2dbc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.junit.jupiter.api.Test;
import org.springframework.boot.r2dbc.ConnectionFactoryBuilder;
import pe.axiz.core.commons.net.DataTransportSecurityPolicy;

class R2dbcTransportSecurityTest {
  @Test
  void opaqueHooksCannotDisableTheEffectivePeerValidation() {
    var policy = new DataTransportSecurityPolicy("prod", false);
    var base =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.SSL, true)
            .build();
    assertThatCode(() -> R2dbcTransportSecurity.validateOptions(base, policy))
        .doesNotThrowAnyException();
    for (String hook :
        new String[] {
          "sslHostnameVerifier",
          "sslContextBuilderCustomizer",
          "sslEngineCustomizer",
          "sslParametersFactory"
        }) {
      var custom = base.mutate().option(Option.valueOf(hook), new Object()).build();
      assertThatThrownBy(() -> R2dbcTransportSecurity.validateOptions(custom, policy))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void checksTheEffectiveOptionsAfterCustomizationBeforeFirstConnection() {
    var policy = new DataTransportSecurityPolicy("prod", true);
    var secure =
        ConnectionFactoryBuilder.withUrl("r2dbc:postgresql://never-connect.test/payments")
            .username("app")
            .configure(options -> options.option(Option.valueOf("sslMode"), "verify-full"))
            .build();
    assertThatCode(() -> R2dbcTransportSecurity.validate(secure, policy))
        .doesNotThrowAnyException();
    var insecure =
        ConnectionFactoryBuilder.withUrl(
                "r2dbc:postgresql://never-connect.test/payments?sslMode=verify-full")
            .username("app")
            .configure(options -> options.option(Option.valueOf("sslMode"), "disable"))
            .build();
    assertThatThrownBy(() -> R2dbcTransportSecurity.validate(insecure, policy))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void h2NetworkConnectionsDoNotUseTheEmbeddedExceptionAndPoolOptionsRemainInspectable() {
    var local = new DataTransportSecurityPolicy("test", false);
    var remote =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "h2")
            .option(ConnectionFactoryOptions.PROTOCOL, "tcp")
            .option(ConnectionFactoryOptions.HOST, "database.test")
            .build();
    assertThatThrownBy(() -> R2dbcTransportSecurity.validateOptions(remote, local))
        .isInstanceOf(IllegalStateException.class);
    var embedded =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "h2")
            .option(ConnectionFactoryOptions.PROTOCOL, "mem")
            .build();
    assertThatCode(() -> R2dbcTransportSecurity.validateOptions(embedded, local))
        .doesNotThrowAnyException();
    var pooled =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "pool")
            .option(ConnectionFactoryOptions.PROTOCOL, "postgresql")
            .option(Option.valueOf("sslMode"), "verify-full")
            .build();
    assertThatCode(
            () ->
                R2dbcTransportSecurity.validateOptions(
                    pooled, new DataTransportSecurityPolicy("prod", false)))
        .doesNotThrowAnyException();
  }
}
