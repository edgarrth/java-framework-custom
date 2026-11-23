package pe.axiz.spring.messaging;

import pe.axiz.core.messaging.AsyncEventPublisher;

/**
 * Adaptador nativo de broker con nombre; independiente de los adaptadores imperativos de sondeo y
 * relay.
 */
public record AsyncEventPublisherAdapter(String name, AsyncEventPublisher publisher) {}
