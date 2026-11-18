package pe.axiz.spring.sensitive;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import pe.axiz.core.commons.Environments;
import pe.axiz.core.sensitive.MaskingPolicy;
import pe.axiz.core.sensitive.ObjectMasker;
import pe.axiz.core.sensitive.OutputChannel;
import pe.axiz.core.sensitive.SensitiveField;
import pe.axiz.core.sensitive.SensitiveKeys;
import tools.jackson.databind.json.JsonMapper;

/**
 * Masking of sensitive data in the three outputs: API responses (the JSON converter or encoder of
 * server responses), logs and audit ({@link ObjectMasker} shared by the logging and audit
 * starters). Active by default. Only the response channel is masked: the application's {@link
 * JsonMapper} keeps the values, since events, saga contexts, caches and outgoing calls need them.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@ConditionalOnBooleanProperty(name = "axiz.sensitive.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AxizSensitiveProperties.class)
public class AxizSensitiveAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  MaskingPolicy axizMaskingPolicy(AxizSensitiveProperties p) {
    return new MaskingPolicy(
        Map.of(
            OutputChannel.RESPONSE,
            p.response(),
            OutputChannel.LOG,
            p.log(),
            OutputChannel.AUDIT,
            p.audit()),
        p.fullMaskLevel());
  }

  @Bean
  @ConditionalOnMissingBean
  SensitiveKeys axizSensitiveKeys(AxizSensitiveProperties p) {
    Map<String, SensitiveField> extra = new HashMap<>();
    p.keys().forEach((name, k) -> extra.put(name, new SensitiveField(name, k.level(), k.mask())));
    return SensitiveKeys.withExtra(extra);
  }

  @Bean
  @ConditionalOnMissingBean
  ObjectMasker axizObjectMasker(
      MaskingPolicy policy,
      SensitiveKeys keys,
      AxizSensitiveProperties properties,
      Environment environment) {
    if (!properties.detectPan()) {
      Environments.requireSingleProcess(
          environment.getProperty("axiz.config.environment"),
          "axiz.sensitive.detect-pan=false",
          "PAN detection enabled",
          "sensitive data detection cannot be disabled in a deployed environment");
    }
    return new ObjectMasker(policy, keys, properties.detectPan());
  }

  /**
   * The masking mapper of API responses: the application's {@link JsonMapper} plus {@link
   * SensitiveJacksonModule}. The application's mapper itself stays unmasked, because it also
   * serializes data that must keep its values: event payloads, saga contexts, cached values,
   * idempotent results and the bodies of outgoing calls.
   */
  static JsonMapper responseMapper(ObjectProvider<JsonMapper> mapper, MaskingPolicy policy) {
    JsonMapper base = mapper.getIfAvailable(() -> JsonMapper.builder().findAndAddModules().build());
    return base.rebuild().addModule(new SensitiveJacksonModule(policy)).build();
  }

  /** Servlet stack: the JSON converter of server responses masks; client converters do not. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(
      name = {
        "tools.jackson.databind.json.JsonMapper",
        "org.springframework.http.converter.json.JacksonJsonHttpMessageConverter",
        "org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer"
      })
  static class ServletResponses {

    /** Runs after Boot's JSON converter customizer (order 0) and replaces its converter. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    ServerHttpMessageConvertersCustomizer axizSensitiveResponseConverter(
        ObjectProvider<JsonMapper> mapper, MaskingPolicy policy) {
      return builder ->
          builder.withJsonConverter(
              new JacksonJsonHttpMessageConverter(responseMapper(mapper, policy)));
    }
  }

  /** Reactive stack: the JSON encoder of server responses masks; WebClient codecs do not. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  @ConditionalOnClass(
      name = {
        "tools.jackson.databind.json.JsonMapper",
        "org.springframework.web.reactive.config.WebFluxConfigurer"
      })
  static class ReactiveResponses {

    /** Ordered after Boot's WebFlux configurer (order 0), which applies the codec customizers. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    WebFluxConfigurer axizSensitiveResponseEncoder(
        ObjectProvider<JsonMapper> mapper, MaskingPolicy policy) {
      return new WebFluxConfigurer() {
        @Override
        public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
          configurer
              .defaultCodecs()
              .jacksonJsonEncoder(new JacksonJsonEncoder(responseMapper(mapper, policy)));
        }
      };
    }
  }
}
