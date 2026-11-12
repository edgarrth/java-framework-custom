import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * Resolves actual web identity using only classes extracted from a final, non-Kafka service JAR.
 */
class IrsaPackagedProbe {
  public static void main(String[] args) throws Exception {
    try {
      Class.forName("org.apache.kafka.clients.producer.KafkaProducer");
      throw new AssertionError("The no-Kafka acceptance consumer unexpectedly contains Kafka");
    } catch (ClassNotFoundException expected) {
      // STS must be supplied by the framework's AWS integration, not MSK authentication.
    }
    AwsCredentialsProvider source;
    SdkAutoCloseable resource;
    try {
      Object handle = Class.forName("pe.axiz.adapter.aws.identity.AwsCredentialSources")
          .getMethod("create").invoke(null);
      resource = (SdkAutoCloseable) handle;
      source = (AwsCredentialsProvider) handle.getClass().getMethod("provider").invoke(handle);
    } catch (ClassNotFoundException noFrameworkOwnedClient) {
      // A Parameter Store-only consumer uses the Spring Cloud AWS bootstrap integration.
      // This checks its STS runtime closure, not the credential-selection policy of that library.
      var owned = WebIdentityTokenFileCredentialsProvider.create();
      source = owned;
      resource = owned;
    }
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
