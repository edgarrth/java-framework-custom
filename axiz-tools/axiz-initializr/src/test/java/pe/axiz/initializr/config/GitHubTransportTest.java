package pe.axiz.initializr.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubTransportTest {
  @Test
  void transportBoundsConnectionAndNeverForwardsCredentialsThroughRedirects() {
    var configuration = new GitHubTransport();
    try (var client = configuration.githubHttpClient()) {
      assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
      assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
      assertThat(configuration.githubRestClient(client).build()).isNotNull();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://api.github.test",
        "https://user:pass@api.github.test",
        "https://api.github.test?secret=value",
        "https://api.github.test#fragment",
        "https:///missing-host"
      })
  void configuredProviderCannotLeakBearerCredentialsThroughUnsafeUrls(String address) {
    assertThatThrownBy(
            () -> new InitializrProperties("2.0.0", URI.create(address), null, 100, "initializr"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
