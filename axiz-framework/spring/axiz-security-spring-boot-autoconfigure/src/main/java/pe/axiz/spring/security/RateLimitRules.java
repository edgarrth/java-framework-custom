package pe.axiz.spring.security;

import java.util.List;
import java.util.Optional;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.security.AxizPrincipal;
import pe.axiz.core.security.RateLimitKeys;

/** Resolves the rate-limit rule and key of a request; the key always comes from the token. */
public class RateLimitRules {

  private final List<Compiled> rules;

  public RateLimitRules(List<AxizSecurityProperties.Rule> rules) {
    this.rules =
        rules.stream()
            .map(
                r ->
                    new Compiled(
                        r,
                        r.paths().stream().map(PathPatternParser.defaultInstance::parse).toList()))
            .toList();
  }

  public Optional<AxizSecurityProperties.Rule> match(String path) {
    PathContainer p = PathContainer.parsePath(path);
    return rules.stream()
        .filter(c -> c.patterns().stream().anyMatch(x -> x.matches(p)))
        .map(Compiled::rule)
        .findFirst();
  }

  public static String key(AxizSecurityProperties.Rule rule, AxizPrincipal principal) {
    return "subject".equalsIgnoreCase(rule.key())
        ? RateLimitKeys.forSubject(principal, rule.name())
        : RateLimitKeys.forClient(principal, rule.name());
  }

  private record Compiled(AxizSecurityProperties.Rule rule, List<PathPattern> patterns) {}
}
