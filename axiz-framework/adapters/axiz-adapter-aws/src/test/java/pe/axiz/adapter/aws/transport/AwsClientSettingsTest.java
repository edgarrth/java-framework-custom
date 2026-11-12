package pe.axiz.adapter.aws.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class AwsClientSettingsTest {
  @Test
  void absentEmptyAndBlankServiceEndpointUseTheEffectiveGlobalEndpoint() {
    for (String specific : new String[] {null, "", " ", "\t"}) {
      var properties = new HashMap<String, String>();
      properties.put("axiz.aws.region", " us-east-1 ");
      properties.put("axiz.aws.endpoint", " http://127.0.0.1:4566 ");
      if (specific != null) properties.put("axiz.aws.s3.endpoint", specific);
      for (var policy : AwsClientSettings.RegionPolicy.values()) {
        var settings = AwsClientSettings.resolve(properties::get, "s3", policy);
        assertEquals(URI.create("http://127.0.0.1:4566"), settings.endpoint());
        assertEquals("us-east-1", settings.region().id());
        assertTrue(settings.endpointOverridden());
      }
    }
  }

  @Test
  void specificEndpointWinsAndBlankGlobalDoesNotCreateAnOverride() {
    var properties = new HashMap<String, String>();
    properties.put("axiz.aws.endpoint", "http://127.0.0.1:4566");
    properties.put("axiz.aws.s3.endpoint", "http://127.0.0.1:9324");
    var settings =
        AwsClientSettings.resolve(properties::get, "s3", AwsClientSettings.RegionPolicy.SDK_CHAIN);
    assertEquals(URI.create("http://127.0.0.1:9324"), settings.endpoint());
    assertNull(settings.region());
    properties.put("axiz.aws.s3.endpoint", "");
    properties.put("axiz.aws.endpoint", " ");
    settings =
        AwsClientSettings.resolve(properties::get, "s3", AwsClientSettings.RegionPolicy.SDK_CHAIN);
    assertNull(settings.endpoint());
    assertFalse(settings.endpointOverridden());
    properties.put("axiz.aws.endpoint", "invalid uri");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AwsClientSettings.resolve(
                properties::get, "s3", AwsClientSettings.RegionPolicy.SDK_CHAIN));
  }

  @Test
  void nativeResolutionRequiresExplicitRegionAndNeverLoadsTheSdkRegionChain() {
    Assumptions.assumeTrue(System.getenv("AWS_REGION") == null);
    assertThrows(
        IllegalStateException.class,
        () -> AwsClientSettings.resolve(k -> null, "s3", AwsClientSettings.RegionPolicy.EXPLICIT));
    assertThrows(
        IllegalStateException.class,
        () -> AwsClientSettings.resolve(k -> " ", "s3", AwsClientSettings.RegionPolicy.EXPLICIT));
    assertNull(
        AwsClientSettings.resolve(k -> null, "s3", AwsClientSettings.RegionPolicy.SDK_CHAIN)
            .region());
    assertThrows(
        NullPointerException.class, () -> AwsClientSettings.resolve(k -> null, "s3", null));
  }
}
