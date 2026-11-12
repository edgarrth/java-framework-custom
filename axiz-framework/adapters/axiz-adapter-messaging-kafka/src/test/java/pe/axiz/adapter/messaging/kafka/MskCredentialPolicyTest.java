package pe.axiz.adapter.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.messaging.kafka.aws.AxizIamClientCallbackHandler;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.msk.auth.iam.internals.AWSCredentialsCallback;
import software.amazon.msk.auth.iam.internals.region.ConfigurableRegionProvider;

@Isolated("Uses temporary SDK system credentials to exercise callback ownership")
class MskCredentialPolicyTest {
  @Test
  void explicitBeanProviderIsBorrowedAndDoesNotFallBackToTheDefaultChain() throws Exception {
    var closes = new AtomicInteger();
    class Source implements AwsCredentialsProvider, SdkAutoCloseable {
      @Override
      public AwsCredentials resolveCredentials() {
        return AwsBasicCredentials.create("CUSTOM_MSK", "fixture");
      }

      @Override
      public void close() {
        closes.incrementAndGet();
      }
    }
    var source = new Source();
    var handler = new AxizIamClientCallbackHandler();
    try {
      handler.configure(
          Map.of(
              AxizIamClientCallbackHandler.CREDENTIALS_SOURCE_FACTORY_CONFIG,
              (Supplier<AwsCredentialSource>) () -> AwsCredentialSource.borrowed(source)),
          "AWS_MSK_IAM",
          entries(Map.of()));
      var callback = new AWSCredentialsCallback();
      handler.handle(new Callback[] {callback});
      assertTrue(callback.isSuccessful());
      assertEquals("CUSTOM_MSK", callback.getAwsCredentials().accessKeyId());
    } finally {
      handler.close();
    }
    assertEquals(0, closes.get());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            handler.configure(
                Map.of(
                    AxizIamClientCallbackHandler.CREDENTIALS_SOURCE_FACTORY_CONFIG,
                    "not-a-provider"),
                "AWS_MSK_IAM",
                entries(Map.of())));
  }

  @Test
  void preservesDefaultModeAndOwnsIrsaCallbacksWithoutFallback() throws Exception {
    var keys =
        List.of(
            "aws.roleArn",
            "aws.webIdentityTokenFile",
            "aws.accessKeyId",
            "aws.secretAccessKey",
            "aws.sessionToken",
            "aws.region");
    Map<String, String> previous = new LinkedHashMap<>();
    keys.forEach(key -> previous.put(key, System.getProperty(key)));
    var handler = new AxizIamClientCallbackHandler();
    try {
      keys.forEach(System::clearProperty);
      System.setProperty("aws.region", "us-east-1");
      System.setProperty("aws.accessKeyId", "FIXTUREKEY");
      System.setProperty("aws.secretAccessKey", "fixture-secret");
      assertThrows(IllegalStateException.class, () -> handler.handle(new Callback[0]));
      handler.configure(Map.of(), "AWS_MSK_IAM", entries(Map.of()));
      var ordinary = new AWSCredentialsCallback();
      handler.handle(new Callback[] {ordinary});
      assertTrue(ordinary.isSuccessful());
      System.setProperty("aws.roleArn", "arn:aws:iam::123456789012:role/fixture");
      System.setProperty("aws.webIdentityTokenFile", "/nonexistent/fixture");
      assertThrows(
          IllegalArgumentException.class,
          () -> handler.configure(Map.of(), "PLAIN", entries(Map.of())));
      assertThrows(
          IllegalArgumentException.class,
          () -> handler.configure(Map.of(), "AWS_MSK_IAM", List.of()));
      for (String option : List.of("awsRoleArn", "awsProfileName"))
        assertThrows(
            IllegalArgumentException.class,
            () -> handler.configure(Map.of(), "AWS_MSK_IAM", entries(Map.of(option, "other"))));
      handler.configure(
          Map.of(
              AxizIamClientCallbackHandler.CREDENTIALS_SOURCE_FACTORY_CONFIG,
              (Supplier<AwsCredentialSource>)
                  () ->
                      AwsCredentialSource.borrowed(
                          () -> AwsBasicCredentials.create("CUSTOM_MSK", "fixture"))),
          "AWS_MSK_IAM",
          entries(Map.of("awsMskRegionProvider", TestRegion.class.getName())));
      var callback = new AWSCredentialsCallback();
      handler.handle(new Callback[] {callback});
      assertTrue(callback.isSuccessful());
      assertEquals("CUSTOM_MSK", callback.getAwsCredentials().accessKeyId());
      assertEquals(Region.US_WEST_2, callback.getCustomRegionProvider().getRegion("broker"));
      assertThrows(
          UnsupportedCallbackException.class,
          () -> handler.handle(new Callback[] {new NameCallback("unexpected")}));
      handler.configure(Map.of(), "AWS_MSK_IAM", entries(Map.of()));
      var failed = new AWSCredentialsCallback();
      handler.handle(new Callback[] {failed});
      assertFalse(failed.isSuccessful());
      assertNotNull(failed.getLoadingException());
    } finally {
      handler.close();
      handler.close();
      previous.forEach(
          (key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
          });
    }
  }

  private static List<AppConfigurationEntry> entries(Map<String, String> options) {
    return List.of(
        new AppConfigurationEntry(
            "software.amazon.msk.auth.iam.IAMLoginModule",
            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
            options));
  }

  public static final class TestRegion implements ConfigurableRegionProvider {
    public TestRegion(Map<String, String> options) {}

    @Override
    public Region getRegion() {
      return Region.US_WEST_2;
    }

    @Override
    public Region getRegion(String host) {
      return Region.US_WEST_2;
    }
  }
}
