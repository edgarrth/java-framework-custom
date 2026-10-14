package pe.axiz.spring.messaging;

import pe.axiz.core.messaging.EventPublisher;

/**
 * A broker publisher contributed by an adapter auto-configuration (MSK, SQS, SNS). The messaging
 * starter routes each destination to the first adapter that supports it.
 *
 * @param name adapter name
 * @param publisher publisher
 */
public record EventPublisherAdapter(String name, EventPublisher publisher) {}
