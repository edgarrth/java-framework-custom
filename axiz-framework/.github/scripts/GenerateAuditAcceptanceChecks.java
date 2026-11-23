import java.nio.file.Files;
import java.nio.file.Path;
import pe.axiz.codegen.render.JavaFormatter;

/** Aserciones propias del framework, agregadas después de la generación normal; nunca se entregan a los consumidores. */
class GenerateAuditAcceptanceChecks {
  public static void main(String[] args) throws Exception {
    try (var projects = Files.list(Path.of(args[0]))) {
      for (Path project : projects.filter(Files::isDirectory).toList()) {
        String name = project.getFileName().toString();
        if (!name.startsWith("audit-orders-")) continue;
        boolean reactive = name.contains("-reactive-");
        boolean sink = name.endsWith("-audit-sqs");
        boolean sns = name.endsWith("-outbox-sns");
        String sdk = sns ? "sns.Sns" : "sqs.Sqs";
        String type = (sns ? "Sns" : "Sqs") + (reactive ? "AsyncClient" : "Client");
        String owner =
            sink
                ? "axizAuditAsyncAwsResources"
                : "axizMessaging" + (sns ? "Sns" : "Sqs") + "AsyncResources";
        StringBuilder method =
            new StringBuilder(
                """

                  // Arnés de aceptación del framework: este método no forma parte de la plantilla generada.
                  @Test
                  void verifiesFrameworkOwnedAuditTransport(@Autowired ApplicationContext context,
                      @Autowired MeterRegistry metrics) {
                """);
        method
            .append("assertThat(context.getBeansOfType(")
            .append(type)
            .append(".class)).hasSize(1);\n");
        if (!sink)
          method
              .append("assertThat(context.getBean(")
              .append(type)
              .append(
                  ".class).serviceClientConfiguration().overrideConfiguration().apiCallTimeout())")
              .append(
                  ".contains(context.getBean(AxizMessagingAwsProperties.class).apiCallTimeout());\n");
        if (reactive)
          method
              .append("assertThat(context.containsBean(\"")
              .append(owner)
              .append("\")).isTrue();\n")
              .append("assertThat(metrics.find(\"axiz.transport.pending\").tag(\"transport\", \"")
              .append(owner)
              .append("\").gauge()).isNotNull();\n");
        method.append("}\n");
        try (var sources = Files.walk(project.resolve("src/test/java"))) {
          Path test =
              sources
                  .filter(path -> path.getFileName().toString().endsWith("ApplicationTest.java"))
                  .findFirst()
                  .orElseThrow();
          String source = Files.readString(test);
          if (source.contains("verifiesFrameworkOwnedAuditTransport"))
            throw new IllegalStateException(
                "Regenerate fixtures before adding the harness: " + test);
          String imports =
              "import static org.assertj.core.api.Assertions.assertThat;\n"
                  + "import org.springframework.context.ApplicationContext;\n"
                  + "import io.micrometer.core.instrument.MeterRegistry;\n"
                  + "import software.amazon.awssdk.services."
                  + sdk
                  + (reactive ? "AsyncClient" : "Client")
                  + ";\n"
                  + (sink ? "" : "import pe.axiz.spring.messaging.AxizMessagingAwsProperties;\n");
          source =
              source.replace(
                  "import org.junit.jupiter.api.Test;",
                  imports + "import org.junit.jupiter.api.Test;");
          int end = source.lastIndexOf('}');
          Files.writeString(
              test,
              JavaFormatter.format(source.substring(0, end) + method + source.substring(end)));
        }
      }
    }
  }
}
