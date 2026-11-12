package pe.axiz.adapter.messaging.kafka;

import java.util.Map;

/**
 * Client properties for Amazon MSK with IAM authentication. Credentials come from the default AWS
 * chain (IRSA or EKS Pod Identity); no user or password is configured.
 */
public final class MskIam {

  private MskIam() {}

  public static Map<String, Object> clientProperties() {
    return Map.of(
        "security.protocol", "SASL_SSL",
        "sasl.mechanism", "AWS_MSK_IAM",
        "sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;",
        "sasl.client.callback.handler.class",
            "pe.axiz.adapter.messaging.kafka.aws.AxizIamClientCallbackHandler");
  }

  /** Producer defaults for reliable delivery. */
  public static Map<String, Object> reliableProducer() {
    return Map.of(
        "acks", "all",
        "enable.idempotence", "true",
        "max.in.flight.requests.per.connection", "5",
        "delivery.timeout.ms", "120000");
  }
}
