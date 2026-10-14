package pe.axiz.spring.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AxizAttributes;

class ObservabilityTest {

  @Test
  void contextAttributesAreHighCardinality() {
    RequestMetadata m =
        RequestMetadata.builder("corr-12345678", "req-12345678").channel("mobile").build();
    try (RequestContext.Scope ignored = RequestContext.open(m)) {
      Observation.Context ctx =
          new RequestContextObservationFilter().map(new Observation.Context());
      assertEquals(
          "corr-12345678",
          ctx.getHighCardinalityKeyValue(AxizAttributes.CORRELATION_ID).getValue());
      assertEquals("mobile", ctx.getHighCardinalityKeyValue(AxizAttributes.CHANNEL).getValue());
      assertFalse(ctx.getLowCardinalityKeyValues().iterator().hasNext());
    }
  }

  @Test
  void probesAreNotObserved() {
    IgnoredPathsObservationPredicate p = new IgnoredPathsObservationPredicate(List.of("/actuator"));
    ServerRequestObservationContext probe =
        new ServerRequestObservationContext(
            new MockHttpServletRequest("GET", "/actuator/health"), new MockHttpServletResponse());
    ServerRequestObservationContext api =
        new ServerRequestObservationContext(
            new MockHttpServletRequest("GET", "/actuators"), new MockHttpServletResponse());
    assertFalse(p.test("http.server.requests", probe));
    assertTrue(p.test("http.server.requests", api));
    assertTrue(p.test("other", new Observation.Context()));
  }

  @Test
  void businessErrorsAreRejections() {
    assertTrue(
        AxizObservabilityAutoConfiguration.REJECTIONS.apply(
            new BusinessException(CoreErrors.CONFLICT, "dup")));
    assertFalse(AxizObservabilityAutoConfiguration.REJECTIONS.apply(new IllegalStateException()));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizObservabilityAutoConfiguration.class))
        .withBean(ObservationRegistry.class, ObservationRegistry::create)
        .run(ctx -> assertTrue(ctx.getBeanNamesForType(ArchitectureObservation.class).length == 1));
  }
}
