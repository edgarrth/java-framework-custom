package pe.axiz.quality.stores;

final class NettyLifecycleProbe
    extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>
    implements AutoCloseable {
  final java.util.Queue<String> errors = new java.util.concurrent.ConcurrentLinkedQueue<>();
  private final java.util.List<ch.qos.logback.classic.Logger> loggers;

  NettyLifecycleProbe() {
    loggers =
        java.util.List.of(
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(
                    "software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient"),
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("io.netty.util.concurrent.GlobalEventExecutor"),
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("reactor.core.scheduler.Schedulers"),
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(
                    "io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor"),
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(
                    "reactor.core.publisher.ContextPropagationSupport"));
    start();
    loggers.forEach(logger -> logger.addAppender(this));
  }

  @Override
  protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
    if (event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
      errors.add(event.getLoggerName() + ": " + event.getFormattedMessage());
  }

  @Override
  public void close() {
    loggers.forEach(logger -> logger.detachAppender(this));
    stop();
  }
}
