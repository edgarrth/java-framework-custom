package pe.axiz.core.observability;

/**
 * Puerto para emitir eventos estructurados; el starter de observabilidad los registra en log y
 * agrega eventos al span.
 */
@FunctionalInterface
public interface StructuredEventEmitter {

  void emit(StructuredEvent event);

  static StructuredEventEmitter noop() {
    return event -> {};
  }
}
