package pe.axiz.spring.messaging;

import pe.axiz.core.messaging.EventPublisher;

/**
 * Publicador de broker aportado por la autoconfiguración de un adaptador (MSK, SQS, SNS). El
 * starter de mensajería enruta cada destino al primer adaptador que lo admite.
 *
 * @param name nombre del adaptador
 * @param publisher publicador
 */
public record EventPublisherAdapter(String name, EventPublisher publisher) {}
