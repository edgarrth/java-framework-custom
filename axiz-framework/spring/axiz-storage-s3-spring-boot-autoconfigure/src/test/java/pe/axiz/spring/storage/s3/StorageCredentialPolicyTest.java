package pe.axiz.spring.storage.s3;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Exercises SDK IRSA system properties without changing credentials for other contexts")
class StorageCredentialPolicyTest {
  @Test
  void aPresignerCannotSilentlyIgnoreAnIncompleteIrsaDeclaration() {
    Map<String, String> previous = new LinkedHashMap<>();
    List.of("aws.roleArn", "aws.webIdentityTokenFile")
        .forEach(key -> previous.put(key, System.getProperty(key)));
    try {
      System.setProperty("aws.roleArn", "arn:aws:iam::123456789012:role/fixture");
      System.setProperty("aws.webIdentityTokenFile", " ");
      try (var context =
          new org.springframework.context.annotation.AnnotationConfigApplicationContext(
              pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration.class)) {
        var failure =
            assertThrows(
                IllegalStateException.class,
                () ->
                    new AxizStorageS3AutoConfiguration.ImperativeConfiguration(
                            new AxizStorageS3Properties("fixture", null))
                        .axizS3PresignerIdentity(
                            context.getBean(pe.axiz.spring.aws.AwsIdentity.class)));
        org.junit.jupiter.api.Assertions.assertTrue(
            failure.getMessage().contains("IRSA requires both"));
      }
    } finally {
      previous.forEach(
          (key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
          });
    }
  }
}
