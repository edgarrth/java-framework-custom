import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import pe.axiz.codegen.ProjectGenerator;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.render.JavaFormatter;

/** CI fixture generated through the framework's public API, including real AsyncAPI payloads. */
class GenerateReactiveNativeSample {
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    var request =
        new ProjectRequest(
            "sample-native-orders",
            "pe.axiz",
            "pe.axiz.samples.nativeorders",
            "Reactive native framework acceptance",
            "business",
            Structure.HEXAGONAL,
            Stack.REACTIVE,
            "aurora",
            List.of("messaging-sqs", "audit-sqs"),
            Files.readString(Path.of(args[1])),
            Files.readString(Path.of(args[2])),
            args[3]);
    ProjectGenerator.withDefaults().generate(request).writeTo(output, false);
    // This acceptance fixture needs a destination during AOT; production templates require input.
    Path application = output.resolve("src/main/resources/application.yaml");
    Files.writeString(
        application,
        Files.readString(application)
            .replace(
                "${AXIZ_AUDIT_QUEUE_URL}",
                "${AXIZ_AUDIT_QUEUE_URL:http://localhost:4566/000000000000/native-acceptance-audit}"));
    Files.writeString(
        output.resolve(
            "src/main/java/pe/axiz/samples/nativeorders/config/AcceptanceAwsIdentityConfiguration.java"),
        JavaFormatter.format(
                """
                package pe.axiz.samples.nativeorders.config;

                import org.slf4j.LoggerFactory;
                import pe.axiz.spring.foundation.aws.AwsIdentityFor;
                import pe.axiz.spring.foundation.aws.AwsCapability;
                import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.context.annotation.Primary;
                import software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider;
                import software.amazon.awssdk.auth.credentials.AwsCredentials;
                import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
                import software.amazon.awssdk.utils.SdkAutoCloseable;

                /** Acceptance-only provider: proves the audit qualifier survives AOT. */
                @Configuration(proxyBeanMethods = false)
                class AcceptanceAwsIdentityConfiguration {
                  @Bean
                  org.springframework.boot.ApplicationRunner defaultIdentityAcceptance(org.springframework.core.env.Environment env) {
                    return args -> {
                      if (!env.getProperty("axiz.acceptance.default-identity-probe", Boolean.class, false)) return;
                      pe.axiz.acceptance.AwsContextIdentityProbe.verify();
                      LoggerFactory.getLogger(AcceptanceAwsIdentityConfiguration.class).info("AXIZ_I2_DEFAULT_CONTEXT_VALIDATED");
                    };
                  }

                  @Bean(destroyMethod = "close", defaultCandidate = false)
                  @AwsIdentityFor(capability = AwsCapability.AUDIT)
                  FixtureCredentials acceptanceAuditCredentials(ConfigurableListableBeanFactory beans) {
                    return credentials(beans, "AUDIT");
                  }

                  @Bean(destroyMethod = "close")
                  @Primary
                  FixtureCredentials acceptanceGlobalCredentials(ConfigurableListableBeanFactory beans) {
                    return credentials(beans, "GLOBAL");
                  }

                  private static FixtureCredentials credentials(ConfigurableListableBeanFactory beans, String scope) {
                    if (beans.containsBean("auditTestAwsCredentialsProvider")) {
                      var source = beans.getBean("auditTestAwsCredentialsProvider", AwsCredentialsProvider.class);
                      return new FixtureCredentials(source::resolveCredentials, scope);
                    }
                    return new FixtureCredentials(WebIdentityTokenFileCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build(), scope);
                  }

                  record FixtureCredentials(AwsCredentialsProvider source, String scope) implements AwsCredentialsProvider, SdkAutoCloseable {
                    @Override public AwsCredentials resolveCredentials() {
                      LoggerFactory.getLogger(FixtureCredentials.class).info("AXIZ_D3_{}_PROVIDER_USED", scope);
                      return source.resolveCredentials();
                    }
                    @Override public void close() {
                      if (source instanceof SdkAutoCloseable closeable) closeable.close();
                    }
                  }
                }
                """));
    // Framework-owned native harness: it must not put adapter calls in consumer business packages.
    Path identityProbe = output.resolve("src/main/java/pe/axiz/acceptance/AwsContextIdentityProbe.java");
    Files.createDirectories(identityProbe.getParent());
    Files.writeString(identityProbe, JavaFormatter.format("""
        package pe.axiz.acceptance;

        import java.util.Objects;
        import java.util.function.Supplier;
        import org.springframework.context.annotation.AnnotationConfigApplicationContext;
        import org.springframework.context.support.GenericApplicationContext;
        import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
        import pe.axiz.spring.foundation.aws.AwsIdentity;
        import pe.axiz.spring.foundation.aws.AwsService;
        import pe.axiz.spring.foundation.aws.AwsCapability;


        public final class AwsContextIdentityProbe {
          private static Supplier<GenericApplicationContext> contexts =
              () -> new AnnotationConfigApplicationContext(AxizAwsIdentityAutoConfiguration.class);
          private AwsContextIdentityProbe() {}
          public static void installFactory(Supplier<GenericApplicationContext> factory) {
            contexts = Objects.requireNonNull(factory);
          }
          public static void verify() {
            var context = contexts.get();
            var identity = context.getBean(AwsIdentity.class);
            try (var first = identity.source(AwsService.SQS, AwsCapability.MESSAGING);
                 var second = identity.source(AwsService.KMS, AwsCapability.CRYPTO)) {
              String key = first.provider().resolveCredentials().accessKeyId();
              first.close();
              if (!key.equals(second.provider().resolveCredentials().accessKeyId()))
                throw new IllegalStateException("Default context identity was not shared");
              if (context.getBeansOfType(AwsIdentity.class).size() != 1)
                throw new IllegalStateException("Expected one internal context identity");
            } finally { context.close(); }
          }
        }
        """));
    // Prepare the isolated acceptance context at build time, using the same Spring AOT API as
    // the application. Runtime native code consumes generated bean definitions, not annotation scans.
    Files.writeString(
        output.resolve("src/main/java/pe/axiz/acceptance/IdentityProbeAotProcessor.java"),
        JavaFormatter.format("""
        package pe.axiz.acceptance;

        import javax.lang.model.element.Modifier;
        import org.springframework.aot.generate.ClassNameGenerator;
        import org.springframework.aot.generate.DefaultGenerationContext;
        import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
        import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
        import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
        import org.springframework.context.annotation.AnnotationConfigApplicationContext;
        import org.springframework.context.aot.ApplicationContextAotGenerator;
        import org.springframework.context.support.GenericApplicationContext;
        import org.springframework.javapoet.ClassName;
        import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;

        /** Acceptance-only AOT for a second, isolated identity context. */
        public final class IdentityProbeAotProcessor implements BeanFactoryInitializationAotProcessor {
          @Override
          public BeanFactoryInitializationAotContribution processAheadOfTime(
              ConfigurableListableBeanFactory beans) {
            if (!beans.containsBeanDefinition("defaultIdentityAcceptance")) return null;
            return (generation, initialization) -> {
              var isolatedGeneration = new DefaultGenerationContext(
                  new ClassNameGenerator(ClassName.get(AwsContextIdentityProbe.class), "IdentityAcceptance"),
                  generation.getGeneratedFiles(), generation.getRuntimeHints());
              ClassName initializer;
              try (var isolated = new AnnotationConfigApplicationContext()) {
                isolated.setClassLoader(beans.getBeanClassLoader());
                isolated.register(AxizAwsIdentityAutoConfiguration.class);
                initializer = new ApplicationContextAotGenerator()
                    .processAheadOfTime(isolated, isolatedGeneration);
              }
              var factory = isolatedGeneration.getGeneratedClasses().addForFeature(
                  "ContextFactory", type -> type.addModifiers(Modifier.PUBLIC));
              factory.getMethods().add("create", method -> method
                  .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                  .returns(GenericApplicationContext.class)
                  .addStatement("$T context = new $T()", GenericApplicationContext.class,
                      GenericApplicationContext.class)
                  .addStatement("new $T().initialize(context)", initializer)
                  .addStatement("context.refresh()")
                  .addStatement("return context"));
              isolatedGeneration.writeGeneratedContent();
              var install = initialization.getMethods().add("installIdentityProbeFactory", method -> method
                  .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                  .addStatement("$T.installFactory($T::create)", AwsContextIdentityProbe.class, factory.getName()));
              initialization.addInitializer(install.toMethodReference());
            };
          }
        }
        """));
    Path aotFactories = output.resolve("src/main/resources/META-INF/spring/aot.factories");
    Files.createDirectories(aotFactories.getParent());
    Files.writeString(aotFactories,
        "org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor=pe.axiz.acceptance.IdentityProbeAotProcessor\n");
    Path test =
        output.resolve(
            "src/test/java/pe/axiz/samples/nativeorders/GeneratedEventContractTest.java");
    Files.writeString(
        test,
        JavaFormatter.format(
"""
        package pe.axiz.samples.nativeorders;

        import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
        import java.math.BigDecimal;
        import java.nio.charset.StandardCharsets;
        import java.time.Instant;
        import java.util.Map;
        import org.junit.jupiter.api.Test;
        import pe.axiz.core.messaging.EventEnvelope;
        import pe.axiz.spring.messaging.JacksonPayloadCodec;
        import pe.axiz.samples.nativeorders.adapter.in.messaging.OrderCreatedV1Handler;
        import pe.axiz.samples.nativeorders.domain.event.OrderCreatedV1;
        import tools.jackson.databind.json.JsonMapper;

        class GeneratedEventContractTest {
          @Test
          void preservesDecimalsAndRegistersTheActualContractWithoutAcknowledgingAStub() throws Exception {
            var codec = new JacksonPayloadCodec(JsonMapper.builder().build());
            String amount = "0.123456789012345678901234567890";
            byte[] bytes = ("{\\"id\\":\\"order-1\\",\\"lines\\":[{\\"amount\\":"
                + amount + "}],\\"futureOptionalField\\":true}").getBytes(StandardCharsets.UTF_8);
            var payload = codec.decode(bytes, OrderCreatedV1.class);
            assertEquals(new BigDecimal(amount), payload.lines().getFirst().get("amount"));
            assertEquals(payload, codec.decode(codec.encode(payload), OrderCreatedV1.class));
            var handler = new OrderCreatedV1Handler();
            assertEquals("pe.axiz.order.created.v1", handler.eventType());
            assertEquals(OrderCreatedV1.class, handler.payloadType());
            var event = new EventEnvelope("event-1", "urn:axiz:acceptance", handler.eventType(),
                Instant.EPOCH, null, "application/json", bytes, Map.of());
            assertThrows(UnsupportedOperationException.class, () -> handler.handle(payload, event));
            assertEquals(0, OrderCreatedV1.class.getAnnotations().length);
            assertNotNull(Class.forName("pe.axiz.samples.nativeorders.config.OrderCreatedV1PayloadConfiguration").getAnnotation(
                org.springframework.aot.hint.annotation.RegisterReflectionForBinding.class));
          }
        }
"""));
  }
}
