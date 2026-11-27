package pe.axiz.quality.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import pe.axiz.adapter.architecturefixture.DirectScheduledClient;
import pe.axiz.adapter.architecturefixture.ExecutorOffloadingClient;
import pe.axiz.adapter.architecturefixture.ParallelOffloadingClient;
import pe.axiz.adapter.architecturefixture.TimerOffloadingClient;
import pe.axiz.adapter.architecturefixture.WorkerScheduledClient;
import pe.axiz.kit.architecturefixture.InjectedOffloadingClient;
import pe.axiz.spring.architecturefixture.OffloadingClient;
import pe.axiz.spring.errors.ProblemWebExceptionHandler;
import pe.axiz.spring.security.ReactiveProblemHandlers;

class ErrorRenderingBoundaryTest {
  @Test
  void presentationExceptionDoesNotAllowOffloadingAnotherClient() {
    var allowed =
        new ClassFileImporter()
            .importClasses(ProblemWebExceptionHandler.class, ReactiveProblemHandlers.class);
    assertFalse(
        FrameworkArchitectureTest.FRAMEWORK_DOES_NOT_OFFLOAD_BLOCKING_IO
            .evaluate(allowed)
            .hasViolation());
    var client = new ClassFileImporter().importClasses(OffloadingClient.class);
    var result = FrameworkArchitectureTest.FRAMEWORK_DOES_NOT_OFFLOAD_BLOCKING_IO.evaluate(client);
    assertTrue(result.hasViolation());
    assertTrue(result.getFailureReport().toString().contains("OffloadingClient.call"));
    for (Class<?> forbidden :
        new Class<?>[] {
          InjectedOffloadingClient.class,
          ExecutorOffloadingClient.class,
          DirectScheduledClient.class,
          WorkerScheduledClient.class,
          ParallelOffloadingClient.class,
          TimerOffloadingClient.class
        }) {
      var violation =
          FrameworkArchitectureTest.FRAMEWORK_DOES_NOT_OFFLOAD_BLOCKING_IO.evaluate(
              new ClassFileImporter().importClasses(forbidden));
      assertTrue(violation.hasViolation(), forbidden.getName());
      assertTrue(
          violation.getFailureReport().toString().contains(forbidden.getSimpleName() + ".call"));
    }
  }
}
