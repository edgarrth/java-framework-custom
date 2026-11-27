package pe.axiz.spring.security;

import java.util.List;
import java.util.Optional;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import pe.axiz.core.security.AxizPrincipal;
import pe.axiz.core.security.RateLimitKeys;

/**
 * Resuelve las reglas de forma independiente para los invocantes autenticados y para las
 * direcciones de conexión anónimas.
 */
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
    return match(path, false);
  }

  public Optional<AxizSecurityProperties.Rule> match(String path, boolean anonymous) {
    PathContainer p = PathContainer.parsePath(path);
    return rules.stream()
        .filter(c -> "anonymous-ip".equals(c.rule().key()) == anonymous)
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
