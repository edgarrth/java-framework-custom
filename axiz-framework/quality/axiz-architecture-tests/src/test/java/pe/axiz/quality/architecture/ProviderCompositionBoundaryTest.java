package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProviderCompositionBoundaryTest {
  @Test
  void vaultProvidersOnlyUseTheirOwnBaseAndApprovedTransportInfrastructure() {
    var base = "pe.axiz.spring.tokenization.vault";
    var dynamo = base + ".dynamodb";
    var redis = base + ".redis";
    assertTrue(FrameworkModules.allowedSpringDependency(dynamo, base));
    assertTrue(FrameworkModules.allowedSpringDependency(redis, base));
    assertTrue(FrameworkModules.allowedSpringDependency(dynamo, "pe.axiz.spring.aws"));
    assertFalse(FrameworkModules.allowedSpringDependency(redis, "pe.axiz.spring.aws"));
    assertFalse(FrameworkModules.allowedSpringDependency(base, dynamo));
    assertFalse(FrameworkModules.allowedSpringDependency(dynamo, redis));
    assertFalse(FrameworkModules.allowedSpringDependency(redis, dynamo));
    assertFalse(FrameworkModules.allowedSpringDependency(dynamo, "pe.axiz.spring.tokenization"));
    assertFalse(FrameworkModules.allowedSpringDependency(dynamo, "pe.axiz.spring.dynamodb"));
    assertTrue(
        FrameworkModules.allowedSpringDependency(
            "pe.axiz.spring.tokenization", "pe.axiz.spring.httpclient"));
    assertFalse(
        FrameworkModules.allowedSpringDependency(
            "pe.axiz.spring.httpclient", "pe.axiz.spring.tokenization"));
    assertFalse(FrameworkModules.allowedSpringDependency("pe.axiz.spring.tokenization", base));
  }
}
