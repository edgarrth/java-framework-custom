package pe.axiz.payments.networkgateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.localstack.LocalStackContainer;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.AuditEventJson;
import pe.axiz.core.messaging.CloudEventJson;
import pe.axiz.payments.networkgateway.application.port.out.PaymentNetwork;
import pe.axiz.payments.networkgateway.domain.Transfer;
import pe.axiz.payments.networkgateway.domain.TransferStatus;
import pe.axiz.spring.test.LocalStackTestConfiguration;
import pe.axiz.spring.test.RedisTestConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Starts the whole service against real dependencies in containers (Testcontainers): wiring,
 * configuration, migrations and health. Needs Docker.
 */
@SpringBootTest(
    properties = {
      "axiz.config.environment=test",
      "axiz.transport.allow-plain-http=true",
      "axiz.transport.allow-insecure-data-stores=true"
    })
@AutoConfigureMockMvc
@Import({
  RedisTestConfiguration.class,
  LocalStackTestConfiguration.class,
  PaymentNetworkGatewayApplicationTest.AuditQueue.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentNetworkGatewayApplicationTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class AuditQueue {
    @Bean(destroyMethod = "close")
    SqsClient demoAuditSqs(LocalStackContainer localstack) {
      return SqsClient.builder()
          .endpointOverride(localstack.getEndpoint())
          .region(Region.of(localstack.getRegion()))
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
          .build();
    }

    @Bean
    DynamicPropertyRegistrar demoAuditDestination(SqsClient sqs) {
      String url = sqs.createQueue(request -> request.queueName("demo-audit")).queueUrl();
      return registry -> registry.add("axiz.audit.sqs.queue-url", () -> url);
    }
  }

  @MockitoBean PaymentNetwork network;

  @Test
  void aRetryReplaysTheFirstResponseAndNeverSendsTheTransferTwice(
      @Autowired MockMvc mvc, @Autowired SqsClient sqs, @Autowired Environment env)
      throws Exception {
    String key = "gateway-replay-1";
    when(network.send(any(), eq(key)))
        .thenReturn(new Transfer("NET-1", TransferStatus.SETTLED, "OP-1"));
    String body =
        """
        {"creditorCci":"00219100123456789053","amount":10.00,"currency":"PEN","reference":"OP-1"}
        """;
    var first =
        mvc.perform(
                post("/v1/transfers")
                    .with(
                        jwt()
                            .jwt(
                                token ->
                                    token
                                        .subject("payment-process")
                                        .claim("azp", "payment-process")
                                        .claim("axiz_token_kind", "service"))
                            .authorities(new SimpleGrantedAuthority("SCOPE_payments:process")))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    var retry =
        mvc.perform(
                post("/v1/transfers")
                    .with(
                        jwt()
                            .jwt(
                                token ->
                                    token
                                        .subject("payment-process")
                                        .claim("azp", "payment-process")
                                        .claim("axiz_token_kind", "service"))
                            .authorities(new SimpleGrantedAuthority("SCOPE_payments:process")))
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(retry.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    verify(network, times(1)).send(any(), eq(key));
    var received =
        sqs.receiveMessage(
            request ->
                request
                    .queueUrl(env.getRequiredProperty("axiz.audit.sqs.queue-url"))
                    .waitTimeSeconds(10)
                    .maxNumberOfMessages(10));
    assertThat(received.messages())
        .anySatisfy(
            message -> {
              var envelope = CloudEventJson.read(message.body());
              assertThat(envelope.type()).isEqualTo("pe.axiz.audit.event.v1");
              var event = AuditEventJson.read(new String(envelope.data(), StandardCharsets.UTF_8));
              assertThat(event.action()).isEqualTo("transfer.send");
              assertThat(event.outcome()).isEqualTo(AuditEvent.Outcome.SUCCESS);
              assertThat(event.service()).isEqualTo("payment-network-gateway");
              assertThat(event.integrity()).isNotNull();
            });
  }

  @Test
  void anUnauthenticatedCallerCannotSendMoney(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/v1/transfers")
                .header("Idempotency-Key", "unauthenticated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aCallerWithoutTheProcessScopeCannotSendMoney(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(
            post("/v1/transfers")
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_payments:read")))
                .header("Idempotency-Key", "forbidden")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"creditorCci":"00219100123456789053","amount":10,"currency":"PEN","reference":"OP-1"}
                    """))
        .andExpect(status().isForbidden());
  }

  @Test
  void startsAndReportsHealth(@Autowired MockMvc mvc) throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
