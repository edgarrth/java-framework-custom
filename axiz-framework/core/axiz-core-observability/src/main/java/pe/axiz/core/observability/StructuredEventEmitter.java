package pe.axiz.core.observability;

/** Port to emit structured events; the observability starter logs them and adds span events. */
@FunctionalInterface
public interface StructuredEventEmitter {

  void emit(StructuredEvent event);

  static StructuredEventEmitter noop() {
    return event -> {};
  }
}
