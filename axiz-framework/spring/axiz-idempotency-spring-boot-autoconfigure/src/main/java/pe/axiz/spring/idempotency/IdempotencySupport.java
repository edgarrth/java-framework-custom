package pe.axiz.spring.idempotency;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.Idempotent;
import pe.axiz.core.idempotency.StoredResponse;

/** Shared rules of the servlet and reactive implementations. */
final class IdempotencySupport {

  /** Request attribute holding the {@link IdempotencyService.Proceed} of this execution. */
  static final String KEY_ATTRIBUTE = "axiz.idempotency.key";

  static final String FINGERPRINT_ATTRIBUTE = "axiz.idempotency.fingerprint";
  static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");

  /** Response headers replayed with the stored body. */
  static final List<String> REPLAYED_HEADERS =
      List.of("Content-Type", "Location", "ETag", StoredResponse.BODY_OMITTED_HEADER);

  private IdempotencySupport() {}

  /** The rule on the method, or on its class. */
  static @Nullable Idempotent annotation(Method method, Class<?> targetClass) {
    Idempotent a = AnnotatedElementUtils.findMergedAnnotation(method, Idempotent.class);
    return a != null
        ? a
        : AnnotatedElementUtils.findMergedAnnotation(targetClass, Idempotent.class);
  }

  /** Stable operation name: handler class and method. */
  static String operation(Method method, Class<?> targetClass) {
    return ClassUtils.getUserClass(targetClass).getName() + "#" + method.getName();
  }

  /**
   * Keys are unique per operation and per caller: token issuer, tenant, calling application and end
   * user. A key reused by another caller starts its own execution and never sees this response.
   */
  static String scope(String operation, @Nullable RequestMetadata m) {
    return m == null
        ? IdempotencyService.scope(operation, null, null, null, null)
        : IdempotencyService.scope(operation, m.issuer(), m.tenantId(), m.clientId(), m.subject());
  }

  /** Response kept for replay; bodies above {@code maxResponseSize} are not stored. */
  static StoredResponse stored(
      int status, Map<String, String> headers, byte[] body, int maxResponseSize) {
    return body.length > maxResponseSize
        ? StoredResponse.withoutBody(status, headers)
        : new StoredResponse(status, headers, body);
  }

  static boolean unsafe(String method) {
    return UNSAFE.contains(method.toUpperCase(Locale.ROOT));
  }

  static Map<String, String> replayedHeaders(Function<String, @Nullable String> lookup) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String h : REPLAYED_HEADERS) {
      String v = lookup.apply(h);
      if (v != null) {
        out.put(h, v);
      }
    }
    return out;
  }
}
