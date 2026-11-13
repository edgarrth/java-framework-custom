package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.core.commons.net.TransportSecurityPolicy;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

class AwsEndpointSecurityTest {
  @Test
  void contextEndpointsFailBeforeCredentialsOrNetworkAndPreserveRegionPrecedence() {
    var properties =
        Map.of(
            "axiz.config.environment",
            "prod",
            "axiz.transport.allow-plain-http",
            "true",
            "axiz.aws.region",
            " us-east-1 ",
            "axiz.aws.endpoint",
            "http://localhost:4566");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AwsClientSettings.resolve(
                properties::get, "s3", AwsClientSettings.RegionPolicy.EXPLICIT));
    assertEquals(
        "us-east-1",
        AwsClientSettings.resolveRegion(properties::get, AwsClientSettings.RegionPolicy.EXPLICIT)
            .id());
    assertEquals(
        "eu-west-1",
        AwsClientSettings.resolveRegion(
                Map.of(
                        "axiz.aws.region",
                        "  ",
                        "aws.region",
                        " eu-west-1 ",
                        "AWS_REGION",
                        "us-east-2")
                    ::get,
                AwsClientSettings.RegionPolicy.EXPLICIT)
            .id());
  }

  @Test
  @ResourceLock("SYSTEM_PROPERTIES")
  void sdkGlobalAndServiceOverridesCannotSilentlyIntroducePlaintext() {
    for (String key :
        new String[] {"aws.endpointUrl", "aws.endpointUrl.sts", "aws.endpointUrl.s3"}) {
      String previous = System.getProperty(key);
      try {
        System.setProperty(key, "http://localhost:4566");
        assertThrows(
            IllegalArgumentException.class,
            () ->
                AwsTransportSecurity.validateOverrides(
                    key.endsWith("sts") ? "sts" : "s3", TransportSecurityPolicy.secure()));
        if (!key.endsWith("s3"))
          assertThrows(
              IllegalArgumentException.class,
              () -> AwsCredentialSources.create(TransportSecurityPolicy.secure()));
      } finally {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
      }
    }
  }

  @Test
  void effectiveUriIsCheckedBeforeSigningAndTransmission() {
    var policy = new AwsTransportSecurity(TransportSecurityPolicy.secure());
    var attributes = new ExecutionAttributes();
    var insecure =
        SdkHttpRequest.builder()
            .method(SdkHttpMethod.GET)
            .uri(URI.create("http://localhost:4566"))
            .build();
    var before =
        InterceptorContext.builder()
            .request(GetCallerIdentityRequest.builder().build())
            .httpRequest(insecure)
            .build();
    var transmit = before;
    assertThrows(
        IllegalArgumentException.class, () -> policy.modifyHttpRequest(before, attributes));
    assertThrows(
        IllegalArgumentException.class, () -> policy.beforeTransmission(transmit, attributes));
    var secure =
        SdkHttpRequest.builder()
            .method(SdkHttpMethod.GET)
            .uri(URI.create("https://bank.invalid"))
            .build();
    var accepted = before.toBuilder().httpRequest(secure).build();
    assertEquals(secure, policy.modifyHttpRequest(accepted, attributes));
    assertDoesNotThrow(() -> policy.beforeTransmission(accepted, attributes));
  }
}
