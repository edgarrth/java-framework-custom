package pe.axiz.quality.stores;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.LoggerFactory;

final class NettyLifecycleProbe extends AppenderBase<ILoggingEvent> implements AutoCloseable {
  final Queue<String> errors = new ConcurrentLinkedQueue<>();
  private final List<Logger> loggers;

  NettyLifecycleProbe() {
    loggers =
        List.of(
            (Logger)
                LoggerFactory.getLogger(
                    "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient"),
            (Logger) LoggerFactory.getLogger("io.netty.util.concurrent.GlobalEventExecutor"),
            (Logger) LoggerFactory.getLogger("reactor.core.scheduler.Schedulers"),
            (Logger)
                LoggerFactory.getLogger(
                    "io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor"),
            (Logger) LoggerFactory.getLogger("reactor.core.publisher.ContextPropagationSupport"));
    start();
    loggers.forEach(logger -> logger.addAppender(this));
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (event.getLevel().isGreaterOrEqual(Level.WARN))
      errors.add(event.getLoggerName() + ": " + event.getFormattedMessage());
  }

  @Override
  public void close() {
    loggers.forEach(logger -> logger.detachAppender(this));
    stop();
  }
}
