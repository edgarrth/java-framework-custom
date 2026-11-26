package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.core.i18n.LocaleResolver;
import pe.axiz.core.i18n.MessageResolver;
import pe.axiz.core.logging.LogOperation;
import pe.axiz.core.logging.NoLog;
import pe.axiz.core.observability.ArchitectureObservation;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.tenancy.TenantContext;
import pe.axiz.spring.i18n.AxizI18nAutoConfiguration;
import pe.axiz.spring.logging.AxizLoggingAutoConfiguration;
import pe.axiz.spring.observability.AxizObservabilityAutoConfiguration;
import pe.axiz.spring.tenancy.AxizTenancyAutoConfiguration;
import pe.axiz.spring.tenancy.TenantWebFilter;
import reactor.core.publisher.Mono;

/**
 * Ejemplos de consumidor: invocan contratos públicos y verifican su comportamiento visible desde
 * fuera.
 */
@ExtendWith(OutputCaptureExtension.class)
class CrossCuttingRecipesTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  static class Operations {
    @Bean
    Statements statements() {
      return new Statements();
    }
  }

  public static class Statements {
    @LogOperation(logArguments = true, logResult = false)
    public String generate(@NoLog String account) {
      return "statement:" + account;
    }
  }

  @Test
  void operationLoggingProtectsArgumentsAndResults(CapturedOutput output, TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      int previousOutputLength = output.getAll().length();
      RecipeStacks.runner(stack)
          .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class))
          .withUserConfiguration(Operations.class)
          .run(
              ctx ->
                  assertThat(ctx.getBean(Statements.class).generate("synthetic-private-account"))
                      .isEqualTo("statement:synthetic-private-account"));
      // Una aplicación reactiva previa en la misma JVM deja activo el appender asíncrono de axiz,
      // así
      // que la línea de cierre puede llegar a la consola un instante después de la invocación.
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(output.getAll().substring(previousOutputLength))
                      .contains("operation=Statements.generate", "outcome=success"));
      assertThat(output.getAll().substring(previousOutputLength))
          .doesNotContain("synthetic-private-account");

      evidence.publishEntry("axiz.capability.logging", stack);
    }
  }

  @Test
  void observationsExportOutcomesWithoutSensitiveAttributes(TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      var registry = ObservationRegistry.create();
      List<Observation.Context> stopped = new ArrayList<>();
      registry
          .observationConfig()
          .observationHandler(
              new ObservationHandler<Observation.Context>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                  stopped.add(context);
                }
              });
      RecipeStacks.runner(stack)
          .withConfiguration(AutoConfigurations.of(AxizObservabilityAutoConfiguration.class))
          .withBean(ObservationRegistry.class, () -> registry)
          .run(
              ctx -> {
                String value =
                    ctx.getBean(ArchitectureObservation.class)
                        .observe(
                            "recipe.statement",
                            "generate",
                            Map.of("channel", "mobile"),
                            Map.of("password", "synthetic-secret"),
                            () -> "ready");
                assertThat(value).isEqualTo("ready");
              });
      assertThat(stopped).hasSize(1);
      assertThat(stopped.getFirst().getLowCardinalityKeyValue(AxizAttributes.OUTCOME).getValue())
          .isEqualTo("success");
      assertThat(stopped.getFirst().getHighCardinalityKeyValues().toString())
          .doesNotContain("synthetic-secret");

      evidence.publishEntry("axiz.capability.observability", stack);
    }
  }

  @Test
  void serviceMessagesFollowTheSupportedLanguage(TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      RecipeStacks.runner(stack)
          .withConfiguration(AutoConfigurations.of(AxizI18nAutoConfiguration.class))
          .withPropertyValues("axiz.i18n.basenames=i18n/recipes")
          .run(
              ctx -> {
                var language = ctx.getBean(LocaleResolver.class).resolve("en-US,es;q=0.8");
                var messages = ctx.getBean(MessageResolver.class);
                assertThat(language).isEqualTo(Locale.ENGLISH);
                assertThat(messages.get("statement.ready", language, Map.of("name", "Ana")))
                    .isEqualTo("Statement ready for Ana");
                assertThat(
                        messages.get(
                            "statement.ready",
                            Locale.forLanguageTag("es-PE"),
                            Map.of("name", "Ana")))
                    .isEqualTo("Estado de cuenta listo para Ana");
              });

      evidence.publishEntry("axiz.capability.i18n", stack);
    }
  }

  @Test
  void servletTenantComesFromValidatedIdentityAndIsClearedAfterTheRequest(TestReporter evidence) {
    new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTenancyAutoConfiguration.class))
        .withPropertyValues("axiz.tenancy.enabled=true", "axiz.tenancy.required=true")
        .run(
            ctx -> {
              var filter =
                  ctx.getBean("axizTenantFilter", FilterRegistrationBean.class).getFilter();
              var request = new MockHttpServletRequest("GET", "/statements");
              request.addHeader("X-Tenant-Id", "untrusted-tenant");
              // En un servicio, la cadena de seguridad completa estos metadatos tras validar el
              // JWT.
              var metadata =
                  RequestMetadata.builder("recipe-correlation", "recipe-request")
                      .tenantId("bank-a")
                      .build();
              try (var ignored = RequestContext.open(metadata)) {
                filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (rq, rs) -> assertThat(TenantContext.require()).isEqualTo("bank-a"));
              }
              assertThat(TenantContext.current()).isEmpty();
              assertThatThrownBy(
                      () -> filter.doFilter(request, new MockHttpServletResponse(), (rq, rs) -> {}))
                  .isInstanceOf(ValidationException.class);
            });

    evidence.publishEntry("axiz.capability.tenancy", "servlet");
  }

  @Test
  void reactiveTenantPropagatesInTheSubscriberContext(TestReporter evidence) {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizTenancyAutoConfiguration.class))
        .withPropertyValues("axiz.tenancy.enabled=true", "axiz.tenancy.required=true")
        .run(
            ctx -> {
              var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/statements"));
              exchange
                  .getAttributes()
                  .put(
                      RequestContext.KEY,
                      RequestMetadata.builder("recipe-correlation", "recipe-request")
                          .tenantId("bank-b")
                          .build());
              ctx.getBean(TenantWebFilter.class)
                  .filter(
                      exchange,
                      ex ->
                          Mono.deferContextual(
                              context -> {
                                assertThat(
                                        context.<RequestMetadata>get(RequestContext.KEY).tenantId())
                                    .isEqualTo("bank-b");
                                return Mono.empty();
                              }))
                  .block();
              assertThat(TenantContext.current()).isEmpty();
            });

    evidence.publishEntry("axiz.capability.tenancy", "reactive");
  }
}
