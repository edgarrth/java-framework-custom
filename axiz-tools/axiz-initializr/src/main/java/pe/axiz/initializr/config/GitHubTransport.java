package pe.axiz.initializr.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Transporte dedicado y acotado; las credenciales bearer nunca siguen una redirección del
 * proveedor.
 */
@Configuration(proxyBeanMethods = false)
public class GitHubTransport {
  @Bean
  HttpClient githubHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  RestClient.Builder githubRestClient(HttpClient githubHttpClient) {
    var requests = new JdkClientHttpRequestFactory(githubHttpClient);
    requests.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().requestFactory(requests);
  }
}
