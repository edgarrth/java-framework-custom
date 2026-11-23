package pe.axiz.payments.notifications.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Datos de {@code pe.axiz.audit.event.v1} (rastro de auditoría de los servicios, ya saneado por
 * sensibilidad en el origen). Solo se mapean los campos que necesita el archivo.
 *
 * @param id id del evento de auditoría
 * @param occurredAt instante ISO
 * @param service servicio que auditó
 * @param action acción, por ejemplo {@code account.debit}
 * @param resourceType tipo de recurso
 * @param resourceId id del recurso, si existe
 * @param outcome SUCCESS o FAILURE
 * @param details detalles saneados
 */
public record AuditRecord(
    String id,
    String occurredAt,
    String service,
    String action,
    String resourceType,
    @Nullable String resourceId,
    String outcome,
    @Nullable Map<String, Object> details) {
  public AuditRecord {
    details = details == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }
}
