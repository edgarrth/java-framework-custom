package pe.axiz.spring.aws;

import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/** Reject competing credential-property policies; explicit provider beans remain supported. */
public final class AwsCredentialConfiguration {
  private AwsCredentialConfiguration() {}

  public static void validate(Binder binder) {
    binder
        .bind("spring.cloud.aws.credentials", Bindable.mapOf(String.class, Object.class))
        .ifBound(values -> validate(values, ""));
  }

  private static void validate(Map<?, ?> values, String prefix) {
    values.forEach(
        (key, value) -> {
          String path = prefix + key;
          if (value instanceof Map<?, ?> nested) {
            validate(nested, path + ".");
            return;
          }
          if (value == null || value.toString().isBlank()) return;
          String normalized = path.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
          if ("instanceprofile".equals(normalized)
              && "false".equalsIgnoreCase(value.toString().strip())) return;
          String setting = path.matches("[A-Za-z0-9._-]{1,120}") ? path : "<redacted-setting>";
          throw new IllegalStateException(
              "spring.cloud.aws.credentials."
                  + setting
                  + " is not a managed Axiz credential setting; use workload identity"
                  + " or an explicit application AwsCredentialsProvider bean");
        });
  }
}
