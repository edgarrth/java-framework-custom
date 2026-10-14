package pe.axiz.payments.notifications.event;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Data of {@code pe.axiz.audit.event.v1} (audit trail of the services, already sanitized by
 * sensitivity at the source). Only the fields the archive needs are mapped.
 *
 * @param id audit event id
 * @param occurredAt ISO instant
 * @param service service that audited
 * @param action action, e.g. {@code account.debit}
 * @param resourceType resource type
 * @param resourceId resource id, if any
 * @param outcome SUCCESS or FAILURE
 * @param details sanitized details
 */
public record AuditRecord(
    String id,
    String occurredAt,
    String service,
    String action,
    String resourceType,
    @Nullable String resourceId,
    String outcome,
    @Nullable Map<String, Object> details) {}
