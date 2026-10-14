package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Framework;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.output.GeneratedProject;

class ProjectGeneratorTest {

  private static final Pattern UNRENDERED = Pattern.compile("(?<!\\$)\\{\\{");
  private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
  private final ProjectGenerator generator = ProjectGenerator.withDefaults();

  /** Every valid archetype x framework x stack x persistence combination. */
  private List<ProjectRequest> combinations() {
    List<ProjectRequest> out = new ArrayList<>();
    for (Archetype a : generator.catalog().archetypes()) {
      for (Framework f : a.frameworks()) {
        for (Stack s : a.stacks()) {
          for (String p : a.persistence().isEmpty() ? List.of("none") : a.persistence()) {
            ProjectRequest r = Fixtures.request(a.name(), f, s, p, List.of());
            if (generator.validate(r).valid()) {
              out.add(r);
            }
          }
        }
      }
    }
    return out;
  }

  @Test
  void generatesEveryValidCombination() {
    List<ProjectRequest> all = combinations();
    assertTrue(all.size() >= 25, "combinations: " + all.size());
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    for (ProjectRequest r : all) {
      GeneratedProject p = generator.generate(r);
      String where =
          r.archetype() + "/" + r.framework().id() + "/" + r.stack().id() + "/" + r.persistence();
      for (Map.Entry<String, byte[]> e : p.files().entrySet()) {
        String path = e.getKey();
        String text = new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        if (!path.contains("/templates/")) {
          // GitHub expressions (${{ ... }}) are legitimate; any other {{ is an unrendered tag
          assertFalse(UNRENDERED.matcher(text).find(), where + " unrendered mustache in " + path);
        }
        if (path.endsWith(".java")) {
          Matcher m = PACKAGE.matcher(text);
          assertTrue(m.find(), where + " no package in " + path);
          String dir = path.substring(path.indexOf("/java/") + 6, path.lastIndexOf('/'));
          assertEquals(m.group(1).replace('.', '/'), dir, where + " package/path mismatch " + path);
        }
        if ((path.endsWith(".yaml") || path.endsWith(".yml")) && !path.contains("/templates/")) {
          for (Object ignored : yaml.loadAll(text)) {
            // parses
          }
        }
      }
      assertTrue(p.files().containsKey("pom.xml"), where);
      assertTrue(p.files().containsKey(".axiz/project.yaml"), where);
      assertTrue(p.files().containsKey(".gitleaks.toml"), where);
    }
  }

  @Test
  void hexagonalServiceHasUseCasesControllersAndDeployment() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "business", Framework.HEXAGONAL, Stack.SERVLET, "aurora", List.of("outbox")));
    String base = "src/main/java/pe/axiz/orders/ordersbusiness/";
    for (String f :
        List.of(
            "application/usecase/CreateOrderUseCase.java",
            "application/usecase/ListOrdersUseCase.java",
            "adapter/in/rest/OrdersController.java",
            "adapter/in/rest/CustomersController.java",
            "config/UseCaseConfiguration.java",
            "domain/package-info.java",
            "OrdersBusinessApplication.java")) {
      assertTrue(p.files().containsKey(base + f), f);
    }
    String controller = p.text(base + "adapter/in/rest/OrdersController.java");
    assertTrue(controller.contains("@PostMapping(\"/v1/orders\")"));
    assertTrue(
        controller.contains("createOrderUseCase.execute(new CreateOrderUseCase.Input(body))"));
    assertTrue(controller.contains("ResponseEntity.status(201)"));
    assertTrue(
        p.text(base + "application/usecase/ListOrdersUseCase.java")
            .contains("record Input(@Nullable String status, PageRequest page)"));
    String migration = p.text("src/main/resources/db/migration/V1__init.sql");
    assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS axiz_outbox"));
    assertTrue(p.text("mvnw").startsWith("#!/bin/sh"), "Maven wrapper included");
    assertTrue(p.text(".mvn/wrapper/maven-wrapper.properties").contains("distributionUrl="));
    assertTrue(p.text(".mvn/jvm.config").contains("jdk.compiler"), "Error Prone needs the exports");
    String ci = p.text(".github/workflows/ci.yaml");
    assertTrue(ci.contains("service: orders-business"), ci);
    assertTrue(ci.contains("needs: ci"), "deployments wait for the build and the image");
    assertTrue(ci.contains("image-digest: ${{ needs.ci.outputs.image-digest }}"), ci);
    assertFalse(ci.contains("<%"), "delimiters fully rendered");
    assertTrue(p.text(".github/workflows/cd.yaml").contains("workflow_dispatch"));
    assertTrue(p.text(".github/workflows/release.yaml").contains("release.yaml@v1"));
    assertTrue(p.files().containsKey("release-please-config.json"));
    assertTrue(p.files().containsKey(".release-please-manifest.json"));
    String pom = p.text("pom.xml");
    assertTrue(pom.contains("<artifactId>axiz-outbox-spring-boot-starter</artifactId>"));
    assertTrue(
        pom.contains("<artifactId>axiz-jpa-spring-boot-starter</artifactId>"),
        "outbox requires jpa");
    assertTrue(
        pom.contains("<artifactId>axiz-framework-hexagonal-spring-boot-starter</artifactId>"));
    assertTrue(pom.contains("<artifactId>spring-boot-starter-webmvc</artifactId>"));
    assertTrue(p.files().containsKey("deploy/helm/orders-business/templates/deployment.yaml"));
    assertFalse(p.files().containsKey("deploy/helm/orders-business/templates/cronjob.yaml"));
    assertTrue(p.files().containsKey("src/test/resources/contract-examples/createOrder.json"));
    assertTrue(p.text("src/main/resources/application.yaml").contains("ddl-auto: validate"));
  }

  @Test
  void cqrsReactiveUsesTheBusesOffTheEventLoop() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request("business", Framework.CQRS, Stack.REACTIVE, "dynamodb", List.of()));
    String base = "src/main/java/pe/axiz/orders/ordersbusiness/";
    String controller = p.text(base + "adapter/in/rest/OrdersController.java");
    assertTrue(controller.contains("Mono<ResponseEntity<Order>>"));
    assertTrue(controller.contains("subscribeOn(Schedulers.boundedElastic())"));
    assertTrue(p.files().containsKey(base + "application/command/CreateOrderCommand.java"));
    assertTrue(p.files().containsKey(base + "application/query/GetOrderQuery.java"));
    assertFalse(p.files().containsKey("src/main/resources/db/migration/V1__init.sql"));
    assertTrue(p.text("pom.xml").contains("spring-boot-starter-webflux"));
    assertTrue(p.text("compose.yaml").contains("SERVICES: dynamodb"));
  }

  @Test
  void cqrsReactiveOnAuroraIsNonBlockingEndToEnd() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "business",
                Framework.CQRS,
                Stack.REACTIVE,
                "aurora",
                List.of("outbox", "inbox", "scheduling")));
    String base = "src/main/java/pe/axiz/orders/ordersbusiness/";
    String controller = p.text(base + "adapter/in/rest/OrdersController.java");
    assertTrue(controller.contains("private final ReactiveCommandBus commandBus;"), controller);
    assertTrue(controller.contains("return commandBus.send(new CreateOrderCommand(body))"));
    assertFalse(controller.contains("Schedulers"), "no offloading: the pipeline is reactive");
    String handler = p.text(base + "application/command/CreateOrderHandler.java");
    assertTrue(handler.contains("implements ReactiveCommandHandler<CreateOrderCommand, Order>"));
    assertTrue(handler.contains("public Mono<Order> handle("));
    assertTrue(
        p.text(base + "application/query/GetOrderHandler.java").contains("ReactiveQueryHandler"));
    String pom = p.text("pom.xml");
    assertTrue(pom.contains("<artifactId>axiz-r2dbc-spring-boot-starter</artifactId>"));
    assertFalse(pom.contains("axiz-jpa-spring-boot-starter"), "JPA blocks: never on reactive");
    assertTrue(pom.contains("<artifactId>axiz-core-outbox-r2dbc</artifactId>"));
    assertTrue(pom.contains("<artifactId>axiz-core-inbox-r2dbc</artifactId>"));
    assertTrue(pom.contains("<artifactId>shedlock-provider-r2dbc</artifactId>"));
    String app = p.text("src/main/resources/application.yaml");
    assertTrue(app.contains("r2dbc:postgresql://"), app);
    assertTrue(app.contains("store: r2dbc"), app);
    assertFalse(app.contains("datasource"), app);
    String local = p.text("src/main/resources/application-local.yaml");
    assertTrue(local.contains("url: r2dbc:postgresql://localhost:5432/orders_business"), local);
    String migration = p.text("src/main/resources/db/migration/V1__init.sql");
    assertTrue(migration.contains("only uses R2DBC"));
    assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS axiz_outbox"));
    assertTrue(p.text("compose.yaml").contains("postgres:"));
    assertTrue(
        p.text("src/test/java/pe/axiz/orders/ordersbusiness/OrdersBusinessApplicationTest.java")
            .contains("PostgresTestConfiguration"));
    assertTrue(p.text(".axiz/project.yaml").contains("r2dbc"));
  }

  @Test
  void reactiveEventProcessorsHaveNonBlockingHandlers() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "event-processor", Framework.EDA, Stack.REACTIVE, "aurora", List.of("inbox")));
    String handler =
        p.files().entrySet().stream()
            .filter(e -> e.getKey().endsWith("handler/ExampleCreatedHandler.java"))
            .map(e -> new String(e.getValue(), java.nio.charset.StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    assertTrue(handler.contains("extends ReactiveTypedEventHandler<ExampleCreated>"), handler);
    assertTrue(handler.contains("protected Publisher<Void> on("));
    assertTrue(p.text("pom.xml").contains("axiz-core-inbox-r2dbc"));
  }

  @Test
  void descriptionsAreEscapedForEveryFormat() throws Exception {
    ProjectRequest base =
        Fixtures.request("business", Framework.CQRS, Stack.SERVLET, "aurora", List.of());
    ProjectRequest r =
        new ProjectRequest(
            base.name(),
            base.groupId(),
            base.basePackage(),
            "History: read model <R2DBC> & \"events\" -- see */ docs",
            base.archetype(),
            base.framework(),
            base.stack(),
            base.persistence(),
            base.capabilities(),
            base.openApi(),
            null,
            base.frameworkVersion());
    GeneratedProject p = generator.generate(r);
    Object chart =
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .load(p.text("deploy/helm/orders-business/Chart.yaml"));
    assertEquals(
        "History: read model <R2DBC> & \"events\" -- see * / docs",
        ((java.util.Map<?, ?>) chart).get("description"));
    javax.xml.parsers.DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new java.io.ByteArrayInputStream(p.files().get("pom.xml")));
    assertFalse(
        p.text("src/main/java/pe/axiz/orders/ordersbusiness/OrdersBusinessApplication.java")
            .contains("see */"));
  }

  @Test
  void batchRunsAsCronJobWithoutWebServer() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "batch", Framework.BATCH, Stack.SERVLET, "aurora", List.of("scheduling")));
    assertTrue(p.files().containsKey("deploy/helm/orders-batch/templates/cronjob.yaml"));
    assertFalse(p.files().containsKey("deploy/helm/orders-batch/templates/deployment.yaml"));
    assertTrue(
        p.text("src/main/resources/application.yaml").contains("web-application-type: none"));
    assertTrue(
        p.text("src/main/resources/db/migration/V1__init.sql").contains("BATCH_JOB_INSTANCE"));
    assertTrue(p.text("src/main/resources/db/migration/V1__init.sql").contains("shedlock"));
    assertFalse(p.text("pom.xml").contains("spring-boot-starter-webmvc"));
  }

  @Test
  void eventProcessorHasHandlersAndNoControllers() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "event-processor", Framework.EDA, Stack.SERVLET, "dynamodb", List.of("inbox")));
    assertTrue(
        p.files().keySet().stream()
            .anyMatch(f -> f.endsWith("handler/ExampleCreatedHandler.java")));
    assertTrue(p.files().keySet().stream().noneMatch(f -> f.endsWith("Controller.java")));
    assertTrue(
        p.text("pom.xml").contains("axiz-core-inbox-dynamodb"),
        "inbox store follows the persistence");
    assertTrue(p.text("src/main/resources/application.yaml").contains("store: dynamodb"));
    assertTrue(p.text("compose.yaml").contains("kafka:"));
  }

  @Test
  void experienceComposesDownstreamCalls() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "experience", Framework.BFF, Stack.REACTIVE, "none", List.of("idempotency-redis")));
    String base = "src/main/java/pe/axiz/orders/ordersexperience/";
    assertTrue(p.text(base + "composition/OrdersComposition.java").contains("Mono.error("));
    assertTrue(p.text("compose.yaml").contains("valkey"));
    assertTrue(p.text("src/main/resources/application.yaml").contains("store: redis"));
  }

  @Test
  void configurationKeepsSecretsOut() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "integration",
                Framework.HEXAGONAL,
                Stack.SERVLET,
                "none",
                List.of("crypto", "soap")));
    String app = p.text("src/main/resources/application.yaml").toLowerCase(java.util.Locale.ROOT);
    assertFalse(app.contains("password:"));
    String values = p.text("deploy/helm/orders-integration/values.yaml");
    assertTrue(values.contains("- field-key-v1"), "secrets come from the ExternalSecret");
    assertTrue(
        p.text("deploy/helm/orders-integration/values-prod.yaml")
            .contains("# axiz.crypto.kms.key-id:"));
  }

  @Test
  void platformArchetypes() {
    GeneratedProject lib =
        generator.generate(
            Fixtures.request("library", Framework.NONE, Stack.SERVLET, "none", List.of()));
    assertTrue(lib.text("pom.xml").contains("<artifactId>axiz-parent-core</artifactId>"));
    assertTrue(
        lib.files().containsKey("src/main/java/pe/axiz/orders/orderslibrary/OrdersLibrary.java"));
    ProjectRequest starter =
        new ProjectRequest(
            "axiz-geo",
            "pe.axiz",
            "pe.axiz.core.geo",
            "",
            "starter",
            Framework.NONE,
            Stack.SERVLET,
            "none",
            List.of(),
            null,
            null,
            "1.0.0");
    GeneratedProject s = generator.generate(starter);
    assertTrue(
        s.files()
            .containsKey(
                "axiz-geo-spring-boot-autoconfigure/src/main/java/pe/axiz/core/geo/spring/GeoAutoConfiguration.java"));
    assertTrue(
        s.text(
                "axiz-geo-spring-boot-autoconfigure/src/main/java/pe/axiz/core/geo/spring/GeoProperties.java")
            .contains("@ConfigurationProperties(\"axiz.geo\")"));
    assertTrue(
        s.text(
                "axiz-geo-spring-boot-autoconfigure/src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .contains("pe.axiz.core.geo.spring.GeoAutoConfiguration"));
  }

  @Test
  void zipEntriesLiveUnderTheProjectFolder() throws Exception {
    GeneratedProject p =
        generator.generate(
            Fixtures.request("crud", Framework.LAYERED, Stack.SERVLET, "aurora", List.of()));
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    p.writeZip(out);
    try (java.util.zip.ZipInputStream zip =
        new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
      int n = 0;
      for (java.util.zip.ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        assertTrue(e.getName().startsWith("orders-crud/"), e.getName());
        n++;
      }
      assertEquals(p.files().size(), n);
    }
  }
}
