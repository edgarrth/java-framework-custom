package pe.axiz.core.api;

/**
 * A capability may veto generic HTTP body logging, tracing and response caching for its routes. The
 * path is relative to the application and still percent-encoded. Every policy must allow capture
 * before infrastructure buffers a body; this contract never grants access to that body.
 */
@FunctionalInterface
public interface HttpPayloadPolicy {
  boolean permitsCapture(String applicationPath);
}
