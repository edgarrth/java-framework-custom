package pe.axiz.core.messaging;

import org.jspecify.annotations.Nullable;

/**
 * Confirmación de un evento publicado.
 *
 * @param eventId id del evento
 * @param destination tópico, cola o tópico SNS
 * @param brokerMessageId id asignado por el broker (offset, id de mensaje), si existe
 */
public record PublishResult(String eventId, String destination, @Nullable String brokerMessageId) {}
