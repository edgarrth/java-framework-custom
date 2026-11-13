package pe.axiz.spring.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Objects;
import org.springframework.core.MethodParameter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.SyncHandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import pe.axiz.core.commons.PageRequest;

/** WebFlux resolver for {@link PageRequest} controller parameters. */
public class ReactivePageRequestArgumentResolver implements SyncHandlerMethodArgumentResolver {

  private final PageRequestParser parser;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public ReactivePageRequestArgumentResolver(PageRequestParser parser) {
    this.parser = Objects.requireNonNull(parser);
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return PageRequest.class.equals(parameter.getParameterType());
  }

  @Override
  public PageRequest resolveArgumentValue(
      MethodParameter parameter, BindingContext context, ServerWebExchange exchange) {
    MultiValueMap<String, String> q = exchange.getRequest().getQueryParams();
    List<String> sort = q.get("sort");
    return parser.parse(
        q.getFirst("page"),
        q.getFirst("size"),
        sort != null ? sort : List.of(),
        parameter.getParameterAnnotation(Paging.class));
  }
}
