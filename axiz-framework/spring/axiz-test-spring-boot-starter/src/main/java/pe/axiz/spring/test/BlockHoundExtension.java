package pe.axiz.spring.test;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import reactor.blockhound.BlockHound;

/**
 * Fails reactive tests that block an event-loop thread. Mandatory for reactive services:
 * {@code @ExtendWith(BlockHoundExtension.class)}.
 */
public class BlockHoundExtension implements BeforeAllCallback {

  private static boolean installed;
  private static final java.util.Queue<BlockingCall> DETECTED =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  private static final java.util.concurrent.atomic.AtomicInteger RECORDED =
      new java.util.concurrent.atomic.AtomicInteger();

  /** Bounded diagnostics preserve the original blocking site even when a driver masks the error. */
  public record BlockingCall(
      String thread, String method, java.util.List<StackTraceElement> stack) {
    public BlockingCall {
      stack = java.util.List.copyOf(stack);
    }
  }

  public static java.util.List<BlockingCall> detectedBlockingCalls() {
    return java.util.List.copyOf(DETECTED);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    install();
  }

  static synchronized void install() {
    if (!installed) {
      BlockHound.builder()
          .loadIntegrations()
          .nonBlockingThreadPredicate(
              existing ->
                  existing.or(
                      thread ->
                          thread.getName().startsWith("axiz-aws-future-")
                              || thread.getName().startsWith("aws-java-sdk-NettyEventLoop")
                              || thread.getName().startsWith("lettuce-nioEventLoop-")
                              || thread.getName().startsWith("lettuce-epollEventLoop-")
                              || thread.getName().startsWith("lettuce-kqueueEventLoop-")
                              || thread.getName().startsWith("lettuce-eventExecutorLoop-")))
          // Only the asynchronous logging boundary is allowed, never synchronous appenders.
          .allowBlockingCallsInside("ch.qos.logback.core.AsyncAppenderBase", "append")
          // An idle executor worker must wait for work. This exact JDK scheduling boundary does
          // not cover callbacks or permit blocking queue operations in application code.
          .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
          // AWS Netty closes its channel pools on GlobalEventExecutor during client shutdown.
          // Only this transport cleanup boundary is permitted; caller-side client.close() and
          // application callbacks do not inherit this allowance.
          .allowBlockingCallsInside("io.netty.channel.pool.SimpleChannelPool", "close")
          .blockingMethodCallback(
              method -> {
                var failure = new reactor.blockhound.BlockingOperationError(method);
                if (RECORDED.getAndUpdate(count -> count < 32 ? count + 1 : count) < 32)
                  DETECTED.add(
                      new BlockingCall(
                          Thread.currentThread().getName(),
                          method.toString(),
                          java.util.List.of(failure.getStackTrace())));
                throw failure;
              })
          .install();
      installed = true;
    }
  }
}
