package pe.axiz.spring.messaging;

import pe.axiz.core.messaging.AsyncEventPublisher;

/** Named native broker adapter; independent of the imperative poll/relay adapters. */
public record AsyncEventPublisherAdapter(String name, AsyncEventPublisher publisher) {}
