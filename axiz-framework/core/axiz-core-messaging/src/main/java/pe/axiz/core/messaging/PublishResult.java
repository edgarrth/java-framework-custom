package pe.axiz.core.messaging;

import org.jspecify.annotations.Nullable;

/**
 * Confirmation of a published event.
 *
 * @param eventId event id
 * @param destination topic, queue or SNS topic
 * @param brokerMessageId id assigned by the broker (offset, message id), if any
 */
public record PublishResult(String eventId, String destination, @Nullable String brokerMessageId) {}
