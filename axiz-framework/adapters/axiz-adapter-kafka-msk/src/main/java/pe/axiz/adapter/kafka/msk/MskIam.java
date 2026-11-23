package pe.axiz.adapter.kafka.msk;

import java.util.Map;

/**
 * Propiedades de cliente para Amazon MSK con autenticación IAM. Las credenciales provienen de la
 * cadena por defecto de AWS (IRSA o EKS Pod Identity); no se configura usuario ni contraseña.
 */
public final class MskIam {

  private MskIam() {}

  public static Map<String, Object> clientProperties() {
    return Map.of(
        "security.protocol", "SASL_SSL",
        "sasl.mechanism", "AWS_MSK_IAM",
        "sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;",
        "sasl.client.callback.handler.class",
            "pe.axiz.adapter.kafka.msk.AxizIamClientCallbackHandler");
  }
}
