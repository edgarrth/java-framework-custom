package pe.axiz.spring.audit;

import pe.axiz.core.audit.AuditPublisher;

/**
 * Destino de eventos de auditoría aportado por la autoconfiguración de un adaptador. El starter de
 * auditoría combina todos los destinos (o el publicador de log cuando no hay ninguno) detrás del
 * publicador asíncrono.
 *
 * @param name nombre del destino para diagnóstico
 * @param publisher publicador
 */
public record AuditSink(String name, AuditPublisher publisher) {}
