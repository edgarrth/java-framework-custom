package pe.axiz.spring.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class BlockHoundStartupTest {
  @TempDir Path temporary;

  @Test
  void optedInPlanInstallsBeforeAnEarlierUnannotatedTestCreatesItsScheduler() throws Exception {
    runProbe("reactive");
  }

  @Test
  void servletOnlyPlanDoesNotInstallTheOptionalInstrumentation() throws Exception {
    runProbe("servlet");
  }

  private void runProbe(String mode) throws Exception {
    Path log = temporary.resolve(mode + "-startup.log");
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+AllowRedefinitionToAddDeleteMethods",
                "-XX:+EnableDynamicAgentLoading",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(),
                mode)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), Files.readString(log));
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  @org.junit.jupiter.api.Order(1)
  public static class EarlierPlainChecks {
    @Test
    void infrastructureWasInstalledBeforeThisFirstPlainTest() {
      var error =
          assertThrows(
              CompletionException.class,
              () ->
                  Mono.fromCallable(
                          () -> {
                            Thread.sleep(5);
                            return "unexpected";
                          })
                      .subscribeOn(Schedulers.parallel())
                      .toFuture()
                      .join());
      assertInstanceOf(BlockingOperationError.class, error.getCause());
    }
  }

  @org.junit.jupiter.api.Order(2)
  @ExtendWith(BlockHoundExtension.class)
  public static class LaterReactiveChecks {
    @Test
    void earlierSchedulerContinuesToExecuteAfterItsIdleWait() {
      assertEquals(
          "active",
          Mono.delay(Duration.ofMillis(25)).map(ignored -> "active").block(Duration.ofSeconds(3)));
    }
  }

  public static class ServletOnlyChecks {
    @Test
    void noDetectionWasRequested() {
      assertEquals(
          "plain",
          Mono.fromCallable(
                  () -> {
                    Thread.sleep(5);
                    return "plain";
                  })
              .subscribeOn(Schedulers.parallel())
              .block(Duration.ofSeconds(3)));
    }
  }

  public static final class Probe {
    private Probe() {}

    public static void main(String[] args) {
      var asynchronousErrors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
      Schedulers.onHandleError(
          "startup-regression", (thread, error) -> asynchronousErrors.add(error));
      var summary = new SummaryGeneratingListener();
      boolean reactive = "reactive".equals(args[0]);
      var builder =
          LauncherDiscoveryRequestBuilder.request()
              .configurationParameter(
                  "junit.jupiter.testclass.order.default",
                  "org.junit.jupiter.api.ClassOrderer$OrderAnnotation");
      if (reactive)
        builder.selectors(
            DiscoverySelectors.selectClass(EarlierPlainChecks.class),
            DiscoverySelectors.selectClass(LaterReactiveChecks.class));
      else builder.selectors(DiscoverySelectors.selectClass(ServletOnlyChecks.class));
      var request = builder.build();
      try {
        LauncherFactory.create().execute(request, summary);
        summary.getSummary().printTo(new java.io.PrintWriter(System.out, true));
        assertEquals(reactive ? 2 : 1, summary.getSummary().getTestsSucceededCount());
        assertTrue(asynchronousErrors.isEmpty(), asynchronousErrors.toString());
      } finally {
        Schedulers.shutdownNow();
        Schedulers.resetOnHandleError("startup-regression");
      }
    }
  }
}
