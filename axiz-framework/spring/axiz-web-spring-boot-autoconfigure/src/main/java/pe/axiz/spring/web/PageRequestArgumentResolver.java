package pe.axiz.spring.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pe.axiz.core.api.PageRequest;

/** Spring MVC resolver for {@link PageRequest} controller parameters. */
public class PageRequestArgumentResolver implements HandlerMethodArgumentResolver {

  private final PageRequestParser parser;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Rejects invalid construction; no finalizer or publication of this. The overridable "
              + "Spring SPI remains extensible; this constructor acquires no external resource.")
  public PageRequestArgumentResolver(PageRequestParser parser) {
    this.parser = Objects.requireNonNull(parser);
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return PageRequest.class.equals(parameter.getParameterType());
  }

  @Override
  public PageRequest resolveArgument(
      MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      NativeWebRequest request,
      @Nullable WebDataBinderFactory binderFactory) {
    String[] sort = request.getParameterValues("sort");
    List<String> sorts = sort != null ? Arrays.asList(sort) : List.of();
    return parser.parse(
        request.getParameter("page"),
        request.getParameter("size"),
        sorts,
        parameter.getParameterAnnotation(Paging.class));
  }
}
