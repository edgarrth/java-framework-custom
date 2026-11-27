package pe.axiz.core.logging;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * Proveedor SLF4J de las pruebas de este módulo: conserva cada evento en memoria, con el MDC del
 * momento, para que las pruebas verifiquen lo que se escribe (y lo que no) en lugar de solo llamar
 * a la API.
 */
public final class CapturedLogs implements SLF4JServiceProvider {

  /** Evento registrado. */
  public record Event(
      Level level, String message, Map<String, String> mdc, @Nullable Throwable error) {}

  private static final List<Event> EVENTS = new CopyOnWriteArrayList<>();
  private static volatile Level threshold = Level.INFO;

  private final IMarkerFactory markers = new BasicMarkerFactory();
  private final MDCAdapter mdc = new BasicMDCAdapter();
  private final ILoggerFactory loggers = Captured::new;

  /** Inicia una prueba: descarta los eventos previos y fija el nivel más bajo que se escribe. */
  public static void reset(Level lowest) {
    EVENTS.clear();
    threshold = lowest;
  }

  /** Eventos escritos desde el último {@link #reset}. */
  public static List<Event> events() {
    return List.copyOf(EVENTS);
  }

  @Override
  public ILoggerFactory getLoggerFactory() {
    return loggers;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return markers;
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return mdc;
  }

  @Override
  public String getRequestedApiVersion() {
    return "2.0.99";
  }

  @Override
  public void initialize() {
    // nada que preparar
  }

  private static final class Captured extends LegacyAbstractLogger {

    private static final long serialVersionUID = 1L;

    Captured(String name) {
      this.name = name;
    }

    private static boolean enabled(Level level) {
      return level.toInt() >= threshold.toInt();
    }

    @Override
    public boolean isTraceEnabled() {
      return enabled(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
      return enabled(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
      return enabled(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
      return enabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
      return enabled(Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return Captured.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
        Level level,
        @Nullable Marker marker,
        String pattern,
        @Nullable Object[] arguments,
        @Nullable Throwable error) {
      Map<String, String> context = MDC.getCopyOfContextMap();
      EVENTS.add(
          new Event(
              level,
              MessageFormatter.basicArrayFormat(pattern, arguments),
              context == null ? Map.of() : Map.copyOf(context),
              error));
    }
  }
}
