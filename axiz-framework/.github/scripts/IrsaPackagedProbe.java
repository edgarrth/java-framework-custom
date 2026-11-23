import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Resuelve la identidad web real usando solo clases extraídas de un JAR final de servicio sin Kafka.
 */
class IrsaPackagedProbe {
  public static void main(String[] args) throws Exception {
    try {
      Class.forName("org.apache.kafka.clients.producer.KafkaProducer");
      throw new AssertionError("The no-Kafka acceptance consumer unexpectedly contains Kafka");
    } catch (ClassNotFoundException expected) {
      // STS lo aporta la integración AWS del framework, no la autenticación de MSK.
    }
    AwsCredentialsProvider source;
    SdkAutoCloseable resource;
    Object handle =
        Class.forName("pe.axiz.adapter.aws.identity.AwsCredentialSources")
            .getMethod("create", Class.forName("pe.axiz.core.commons.net.TransportSecurityPolicy"))
            .invoke(
                null,
                Class.forName("pe.axiz.core.commons.net.TransportSecurityPolicy")
                    .getMethod("system")
                    .invoke(null));
    resource = (SdkAutoCloseable) handle;
    source = (AwsCredentialsProvider) handle.getClass().getMethod("provider").invoke(handle);
    try {
      var credentials = source.resolveCredentials();
      if (!Boolean.parseBoolean(args[0])) throw new AssertionError("Invalid IRSA was accepted");
      if (!(credentials instanceof AwsSessionCredentials session)
          || !session.accessKeyId().equals("ASIAFIXTURE")
          || !session.sessionToken().equals("fixture-session"))
        throw new AssertionError("The packaged SDK did not resolve temporary STS credentials");
      System.out.println("PASS packaged IRSA temporary credentials without Kafka");
    } catch (RuntimeException failure) {
      if (Boolean.parseBoolean(args[0])) throw failure;
      System.out.println("PASS packaged invalid IRSA rejected");
    } finally {
      resource.close();
    }
  }
}
