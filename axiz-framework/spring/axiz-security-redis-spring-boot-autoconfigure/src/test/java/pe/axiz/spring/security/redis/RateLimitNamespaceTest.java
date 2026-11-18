package pe.axiz.spring.security.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RateLimitNamespaceTest {
  @Test
  void anEmptyNamespaceCannotMergeUnrelatedApplications() {
    for (String prefix : new String[] {null, "", " "}) {
      assertThatThrownBy(() -> new AxizSecurityRedisProperties(prefix))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new AxizSecurityRedisProperties("payments:limit:").keyPrefix())
        .isEqualTo("payments:limit:");
  }
}
