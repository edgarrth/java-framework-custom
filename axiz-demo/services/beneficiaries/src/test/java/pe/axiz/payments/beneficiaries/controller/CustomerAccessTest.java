package pe.axiz.payments.beneficiaries.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import pe.axiz.core.security.AxizPrincipal;

/** A customer only manages their own beneficiaries; service tokens never do. */
class CustomerAccessTest {

  final CustomerAccess access = new CustomerAccess();

  static TestingAuthenticationToken token(String subject, String client) {
    AxizPrincipal principal =
        AxizPrincipal.builder().subject(subject).clientId(client).issuer("iss").audience(List.of()).scopes(Set.of()).roles(Set.of()).tenantId(null).attributes(Map.of()).build();
    return new TestingAuthenticationToken(principal, null, List.of());
  }

  @Test
  void theCustomerOfThePath() {
    assertThat(access.isCustomer(token("C-1001", "payments-web"), "C-1001")).isTrue();
  }

  @Test
  void anotherCustomerOrAServiceIsRefused() {
    assertThat(access.isCustomer(token("C-1002", "payments-web"), "C-1001")).isFalse();
    assertThat(access.isCustomer(token("local-client", "local-client"), "C-1001")).isFalse();
    assertThat(access.isCustomer(null, "C-1001")).isFalse();
  }

  @Test
  void withoutACustomerInThePathOrAnAxizPrincipalNobodyIsTheCustomer() {
    assertThat(access.isCustomer(token("C-1001", "payments-web"), null)).isFalse();
    assertThat(
            access.isCustomer(new TestingAuthenticationToken("C-1001", null, List.of()), "C-1001"))
        .isFalse();
  }
}
