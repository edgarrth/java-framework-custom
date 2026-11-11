package pe.axiz.spring.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigurationErrorsTest {
  @Test
  void describesActualRoutesAndMissingVariablesWithoutLeakingUriCredentials() {
    assertThat(ConfigurationErrors.destination("audit", "kafka:audits"))
        .contains("destination 'audit'", "resolves to 'kafka:audits'");
    assertThat(ConfigurationErrors.destination("audit", "${AUDIT_ROUTE}/${AUDIT_ROUTE}"))
        .contains("unresolved variables: [AUDIT_ROUTE]");
    assertThat(
            ConfigurationErrors.destination(
                "https://user:secret@host/audit?token=hidden",
                "https://user:secret@host/audit?token=hidden#fragment"))
        .contains("https://host/audit")
        .doesNotContain("user", "secret", "token", "fragment");
    assertThat(ConfigurationErrors.destination("audit", "https://${AUDIT_HOST}/queue"))
        .contains("<invalid URI>", "unresolved variables: [AUDIT_HOST]");
  }
}
