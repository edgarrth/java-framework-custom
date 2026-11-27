package pe.axiz.core.api;

/**
 * Una capacidad puede vetar el logging genérico del cuerpo HTTP, el tracing y la caché de
 * respuestas para sus rutas. La ruta es relativa a la aplicación y sigue codificada con
 * porcentajes. Todas las políticas deben permitir la captura antes de que la infraestructura
 * almacene un cuerpo en buffer; este contrato nunca concede acceso a ese cuerpo.
 */
@FunctionalInterface
public interface HttpPayloadPolicy {
  boolean permitsCapture(String applicationPath);
}
