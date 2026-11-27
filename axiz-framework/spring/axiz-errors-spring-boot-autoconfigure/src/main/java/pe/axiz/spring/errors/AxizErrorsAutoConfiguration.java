package pe.axiz.spring.errors;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import pe.axiz.core.errors.ErrorCatalog;
import pe.axiz.core.errors.ErrorCode;
import pe.axiz.core.errors.ErrorDefinition;
import pe.axiz.core.errors.ErrorMessageResolver;
import pe.axiz.core.errors.ExceptionMapper;
import pe.axiz.core.errors.ProblemFactory;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Respuestas de error RFC 9457 para aplicaciones servlet y reactivas, respaldadas por el catálogo
 * de errores. Los mensajes internos de las excepciones nunca se devuelven a los clientes.
 */
@AutoConfiguration(
    beforeName = {
      "org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration",
      "org.springframework.boot.webflux.autoconfigure.error.ErrorWebFluxAutoConfiguration"
    })
@ConditionalOnBooleanProperty(name = "axiz.errors.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizErrorsProperties.class)
public class AxizErrorsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ErrorCatalog axizErrorCatalog(
      AxizErrorsProperties properties, ObjectProvider<ErrorDefinition> definitions) {
    ErrorCatalog.Builder builder = ErrorCatalog.builder();
    definitions.orderedStream().forEach(builder::add);
    for (AxizErrorsProperties.CatalogEntry e : properties.catalog()) {
      builder.add(
          new ErrorDefinition(
              ErrorCode.of(e.code()),
              e.category(),
              e.type() != null ? e.type() : e.category().defaultType(),
              e.title(),
              e.messageKey() != null ? e.messageKey() : "error." + e.code()));
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnMissingBean
  ProblemFactory axizProblemFactory(
      ErrorCatalog catalog,
      AxizErrorsProperties properties,
      ObjectProvider<ErrorMessageResolver> messages,
      ObjectProvider<ExceptionMapper> mappers,
      ObjectProvider<Clock> clock) {
    List<ExceptionMapper> all = new ArrayList<>(mappers.orderedStream().toList());
    all.add(SpringExceptionMappers.springExceptions());
    return new ProblemFactory(
        catalog,
        messages.getIfAvailable(ErrorMessageResolver::none),
        all,
        new ProblemFactory.Settings(properties.typeBaseUri(), properties.exposeExternalDetail()),
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  @ConditionalOnMissingBean
  ProblemResponder axizProblemResponder(ProblemFactory factory) {
    return new ProblemResponder(factory);
  }

  /**
   * Spring MVC: controller advice y el controlador de errores usado para los errores de filtros y
   * del contenedor.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
  static class ServletConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemControllerAdvice axizProblemControllerAdvice(ProblemResponder responder) {
      return new ProblemControllerAdvice(responder);
    }

    @Bean
    @ConditionalOnMissingBean(type = "org.springframework.boot.webmvc.error.ErrorController")
    ProblemErrorController axizProblemErrorController(ProblemResponder responder) {
      return new ProblemErrorController(responder);
    }
  }

  /** WebFlux: manejador de excepciones que se ejecuta antes del de Boot por defecto. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
  static class ReactiveConfiguration {

    @Bean(destroyMethod = "dispose", defaultCandidate = false)
    @ConditionalOnMissingBean(name = "axizErrorRenderingScheduler")
    Scheduler axizErrorRenderingScheduler() {
      return Schedulers.newBoundedElastic(4, 64, "axiz-error-render", 60, true);
    }

    @Bean
    @Order(-2)
    @ConditionalOnMissingBean
    ProblemWebExceptionHandler axizProblemWebExceptionHandler(
        ProblemResponder responder, @Qualifier("axizErrorRenderingScheduler") Scheduler scheduler) {
      return new ProblemWebExceptionHandler(responder, scheduler);
    }
  }
}
