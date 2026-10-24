package pe.axiz.spring.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

/** Skips server observations for infrastructure paths such as probes and metrics scraping. */
public class IgnoredPathsObservationPredicate implements ObservationPredicate {

  private static final boolean SERVLET =
      ClassUtils.isPresent("jakarta.servlet.http.HttpServletRequest", null)
          && ClassUtils.isPresent(
              "org.springframework.http.server.observation.ServerRequestObservationContext", null);
  private static final boolean REACTIVE =
      ClassUtils.isPresent(
          "org.springframework.http.server.reactive.observation.ServerRequestObservationContext",
          null);

  private final List<String> prefixes;

  public IgnoredPathsObservationPredicate(List<String> prefixes) {
    this.prefixes = List.copyOf(prefixes);
  }

  @Override
  public boolean test(String name, Observation.Context context) {
    String path = path(context);
    return path == null
        || prefixes.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"));
  }

  private static @Nullable String path(Observation.Context context) {
    if (SERVLET
        && context
            instanceof
            org.springframework.http.server.observation.ServerRequestObservationContext s) {
      var request = s.getCarrier();
      return request == null ? null : request.getRequestURI();
    }
    if (REACTIVE
        && context
            instanceof
            org.springframework.http.server.reactive.observation.ServerRequestObservationContext
                r) {
      var request = r.getCarrier();
      return request == null ? null : request.getPath().value();
    }
    return null;
  }
}
