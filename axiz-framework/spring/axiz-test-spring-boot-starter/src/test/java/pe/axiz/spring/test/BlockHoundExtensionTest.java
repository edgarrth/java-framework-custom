package pe.axiz.spring.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@ExtendWith(BlockHoundExtension.class)
class BlockHoundExtensionTest {
  @Test
  void retainsTheOriginalBlockingSiteWhenADriverMasksItWithoutGrowingDiagnosticsUnboundedly()
      throws Exception {
    try (var executor =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("axiz-aws-future-diagnostics").factory())) {
      for (int index = 0; index < 40; index++) {
        assertEquals(
            "masked",
            executor
                .submit(
                    () -> {
                      try {
                        Thread.sleep(5);
                      } catch (BlockingOperationError original) {
                        return "masked";
                      }
                      return "unexpected";
                    })
                .get(3, TimeUnit.SECONDS));
      }
      var calls = BlockHoundExtension.detectedBlockingCalls();
      assertEquals(32, calls.size());
      assertTrue(
          calls.stream()
              .anyMatch(
                  call ->
                      call.thread().equals("axiz-aws-future-diagnostics")
                          && call.method().contains("java.lang.Thread")
                          && !call.stack().isEmpty()));
      assertThrows(UnsupportedOperationException.class, calls::clear);
      assertThrows(UnsupportedOperationException.class, calls.getFirst().stack()::clear);
    }
  }

  @Test
  void idleCompletionWorkersSurviveButApplicationQueueWaitsStillFail() throws Exception {
    var created = new java.util.concurrent.atomic.AtomicInteger();
    var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
    try (var executor =
        Executors.newSingleThreadExecutor(
            work -> {
              created.incrementAndGet();
              Thread thread =
                  Thread.ofPlatform().name("axiz-aws-future-idle-probe").unstarted(work);
              worker.set(thread);
              return thread;
            })) {
      assertEquals("ready", executor.submit(() -> "ready").get(3, TimeUnit.SECONDS));
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(3))
          .until(() -> worker.get().getState() == Thread.State.WAITING);
      assertEquals(1, created.get());
      assertEquals("still alive", executor.submit(() -> "still alive").get(3, TimeUnit.SECONDS));
      var blocked =
          executor.submit(() -> new java.util.concurrent.LinkedTransferQueue<String>().take());
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> blocked.get(3, TimeUnit.SECONDS));
      assertInstanceOf(BlockingOperationError.class, failure.getCause());
    }
  }

  @Test
  void detectsBlockingOnReactorThreads() {
    CompletionException failure =
        assertThrows(
            CompletionException.class,
            () ->
                Mono.fromCallable(
                        () -> {
                          Thread.sleep(5);
                          return "must fail";
                        })
                    .subscribeOn(Schedulers.parallel())
                    .toFuture()
                    .join());
    assertInstanceOf(BlockingOperationError.class, failure.getCause());
  }

  @Test
  void detectsBlockingOnTheAwsCompletionPool() throws Exception {
    try (var executor =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("axiz-aws-future-regression").factory())) {
      var task =
          executor.submit(
              () -> {
                Thread.sleep(5);
                return "must fail";
              });
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
      assertInstanceOf(BlockingOperationError.class, failure.getCause());
    }
  }

  @Test
  void detectsBlockingOnLettuceIoAndCompletionWorkers() throws Exception {
    for (String prefix :
        java.util.List.of(
            "lettuce-nioEventLoop-",
            "lettuce-epollEventLoop-",
            "lettuce-kqueueEventLoop-",
            "lettuce-eventExecutorLoop-")) {
      try (var executor =
          Executors.newSingleThreadExecutor(Thread.ofPlatform().name(prefix + "probe").factory())) {
        var task =
            executor.submit(
                () -> {
                  Thread.sleep(5);
                  return "must fail";
                });
        var failure = assertThrows(ExecutionException.class, () -> task.get(3, TimeUnit.SECONDS));
        assertInstanceOf(BlockingOperationError.class, failure.getCause());
      }
    }
  }
}
