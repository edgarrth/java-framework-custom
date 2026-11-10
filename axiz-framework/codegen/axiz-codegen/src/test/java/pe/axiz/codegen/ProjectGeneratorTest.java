package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.ProjectRequest;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.codegen.render.TemplateSet;

class ProjectGeneratorTest {

  private static final Pattern UNRENDERED = Pattern.compile("(?<!\\$)\\{\\{");
  private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
  private final ProjectGenerator generator = ProjectGenerator.withDefaults();

  @Test
  void architectureImportsAreCompleteWithoutDependingOnTheJvmFormatter() {
    for (boolean cqrs : List.of(false, true)) {
      var request =
          Fixtures.request(
              "business",
              Structure.HEXAGONAL,
              Stack.SERVLET,
              "aurora",
              List.of(),
              cqrs ? new Extension[] {Extension.CQRS} : new Extension[0]);
      var validation = generator.validate(request);
      var files =
          TemplateSet.load("service")
              .render(generator.context(request, validation.archetype(), validation.contract()));
      var source =
          files.entrySet().stream()
              .filter(e -> e.getKey().endsWith("/ArchitectureTest.java"))
              .findFirst()
              .orElseThrow();
      String text = new String(source.getValue(), StandardCharsets.UTF_8);
      for (String type : List.of("Archetype", "Stack", "Structure")) {
        assertTrue(text.contains("import pe.axiz.quality.archunit." + type + ";"), text);
        assertFalse(text.contains("(" + "pe.axiz.quality.archunit." + type + "."), text);
      }
      assertEquals(cqrs, text.contains("import pe.axiz.quality.archunit.Extension;"));
      assertEquals(cqrs, text.contains(".extensions(Extension.CQRS)"));
    }
  }

  /** Every valid archetype x structure x stack x persistence combination. */
  private List<ProjectRequest> combinations() {
    List<ProjectRequest> out = new ArrayList<>();
    for (Archetype a : generator.catalog().archetypes()) {
      for (Structure f : a.structures()) {
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
          r.archetype() + "/" + r.structure().id() + "/" + r.stack().id() + "/" + r.persistence();
      for (Map.Entry<String, byte[]> e : p.files().entrySet()) {
        String path = e.getKey();
        String text = new String(e.getValue(), StandardCharsets.UTF_8);
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
                "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of("outbox")));
    String base = "src/main/java/pe/axiz/orders/orders/";
    for (String f :
        List.of(
            "application/usecase/CreateOrderUseCase.java",
            "application/usecase/ListOrdersUseCase.java",
            "adapter/in/rest/OrdersController.java",
            "adapter/in/rest/CustomersController.java",
            "config/UseCaseConfiguration.java",
            "domain/package-info.java",
            "OrdersApplication.java")) {
      assertTrue(p.files().containsKey(base + f), f);
    }
    String controller = p.text(base + "adapter/in/rest/OrdersController.java");
    assertTrue(controller.contains("@PostMapping(\"/v1/orders\")"));
    assertTrue(
        controller.contains("createOrderUseCase.execute(new CreateOrderUseCase.Input(")
            && controller.contains("mapper.convertValue(body,")
            && !p.text(base + "application/usecase/CreateOrderUseCase.java")
                .contains(".api.model"));
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
    assertTrue(ci.contains("service: orders"), ci);
    Map<?, ?> workflow = new Yaml(new SafeConstructor(new LoaderOptions())).load(ci);
    Map<?, ?> jobs = (Map<?, ?>) workflow.get("jobs");
    assertTrue(
        jobs.values().stream()
            .map(job -> (Map<?, ?>) job)
            .noneMatch(
                job -> {
                  String ref = String.valueOf(job.get("uses"));
                  return List.of(
                          "/service-deploy.yaml@",
                          "/service-cd.yaml@",
                          "/lib-deploy.yaml@",
                          "/lib-promote.yaml@")
                      .stream()
                      .anyMatch(ref::contains);
                }),
        "the producer run must never invoke deployment");
    assertTrue(ci.contains("service-build.yaml@v2.0.0"), ci);
    assertTrue(ci.contains("source-sha: ${{ github.sha }}"), ci);
    assertTrue(ci.contains("version: ${{ needs.parameters.outputs.version }}"), ci);
    assertTrue(ci.contains("base-image:"), ci);
    assertFalse(ci.contains("<%"), "delimiters fully rendered");
    assertSeparatedDeployment(p, "service-deploy");
    assertFalse(p.files().containsKey(".github/workflows/cd.yaml"));
    assertTrue(p.text(".github/workflows/release.yaml").contains("release.yaml@v2.0.0"));
    assertTrue(p.files().containsKey("release-please-config.json"));
    assertTrue(p.files().containsKey(".release-please-manifest.json"));
    // nothing released yet; the first release is the version the pom targets (release-please
    // would propose 1.0.0 without initial-version; .github/scripts/release-version computes it)
    assertTrue(p.text(".release-please-manifest.json").contains("\"0.0.0\""));
    assertEquals("0.0.0", p.text("version.txt").strip());
    assertTrue(
        p.text("release-please-config.json").contains("\"initial-version\": \"0.1.0\""),
        p.text("release-please-config.json"));
    assertTrue(p.text("pom.xml").contains("<version>0.1.0-SNAPSHOT</version>"));
    String pom = p.text("pom.xml");
    assertTrue(pom.contains("<artifactId>axiz-outbox-spring-boot-starter</artifactId>"));
    assertTrue(
        pom.contains("<artifactId>axiz-jpa-spring-boot-starter</artifactId>"),
        "outbox requires jpa");
    assertTrue(
        pom.contains("<artifactId>axiz-structure-hexagonal-spring-boot-starter</artifactId>"));
    assertTrue(pom.contains("<artifactId>spring-boot-starter-webmvc</artifactId>"));
    assertTrue(p.files().containsKey("deploy/helm/orders/templates/deployment.yaml"));
    assertFalse(p.files().containsKey("deploy/helm/orders/templates/cronjob.yaml"));
    assertTrue(p.files().containsKey("src/test/resources/contract-examples/createOrder.json"));
    assertTrue(p.text("src/main/resources/application.yaml").contains("ddl-auto: validate"));
  }

  @Test
  void cqrsReactiveUsesNativeAsyncBusesForDynamoDb() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "business",
                Structure.HEXAGONAL,
                Stack.REACTIVE,
                "dynamodb",
                List.of(),
                Extension.CQRS));
    String base = "src/main/java/pe/axiz/orders/orders/";
    String controller = p.text(base + "adapter/in/rest/OrdersController.java");
    assertTrue(controller.contains("Mono<ResponseEntity<Order>>"));
    assertFalse(controller.contains("boundedElastic"));
    assertTrue(controller.contains("ReactiveCommandBus"));
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
                Structure.HEXAGONAL,
                Stack.REACTIVE,
                "aurora",
                List.of("outbox", "inbox", "scheduling"),
                Extension.CQRS));
    String base = "src/main/java/pe/axiz/orders/orders/";
    String controller = p.text(base + "adapter/in/rest/OrdersController.java");
    assertTrue(controller.contains("private final ReactiveCommandBus commandBus;"), controller);
    assertTrue(
        controller.contains("return commandBus.send(new CreateOrderCommand(")
            && controller.contains("mapper.convertValue(body,")
            && !p.text(base + "application/command/CreateOrderCommand.java")
                .contains(".api.model"));
    assertFalse(controller.contains("Schedulers"), "no offloading: the pipeline is reactive");
    String handler = p.text(base + "application/command/CreateOrderHandler.java");
    assertTrue(handler.contains("implements ReactiveCommandHandler<CreateOrderCommand, Order>"));
    assertTrue(handler.contains("public Mono<Order> handle("));
    assertTrue(
        p.text(base + "application/query/GetOrderHandler.java").contains("ReactiveQueryHandler"));
    String pom = p.text("pom.xml");
    assertTrue(pom.contains("<artifactId>axiz-r2dbc-spring-boot-starter</artifactId>"));
    assertTrue(
        Pattern.compile(
                "<artifactId>axiz-adapter-database-auth-aws</artifactId>\\s*<scope>runtime</scope>")
            .matcher(pom)
            .find(),
        "Generated IAM support must be a runtime adapter: " + pom);
    assertFalse(pom.contains("axiz-jpa-spring-boot-starter"), "JPA blocks: never on reactive");
    assertTrue(pom.contains("<artifactId>axiz-adapter-outbox-r2dbc</artifactId>"));
    assertTrue(pom.contains("<artifactId>axiz-adapter-inbox-r2dbc</artifactId>"));
    assertTrue(pom.contains("<artifactId>shedlock-provider-r2dbc</artifactId>"));
    String app = p.text("src/main/resources/application.yaml");
    assertTrue(app.contains("r2dbc:postgresql://"), app);
    assertTrue(app.contains("store: r2dbc"), app);
    assertFalse(app.contains("datasource"), app);
    String local = p.text("src/main/resources/application-local.yaml");
    assertTrue(local.contains("url: r2dbc:postgresql://localhost:5432/orders"), local);
    String migration = p.text("src/main/resources/db/migration/V1__init.sql");
    assertTrue(migration.contains("only uses R2DBC"));
    assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS axiz_outbox"));
    assertTrue(p.text("compose.yaml").contains("postgres:"));
    assertTrue(
        p.text("src/test/java/pe/axiz/orders/orders/OrdersApplicationTest.java")
            .contains("PostgresTestConfiguration"));
    assertTrue(p.text(".axiz/project.yaml").contains("r2dbc"));
  }

  @Test
  void reactiveEventProcessorsHaveNonBlockingHandlers() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "event-processor", Structure.EDA, Stack.REACTIVE, "aurora", List.of("inbox")));
    String handler =
        p.files().entrySet().stream()
            .filter(e -> e.getKey().endsWith("handler/ExampleCreatedHandler.java"))
            .map(e -> new String(e.getValue(), StandardCharsets.UTF_8))
            .findFirst()
            .orElseThrow();
    assertTrue(handler.contains("extends ReactiveTypedEventHandler<ExampleCreated>"), handler);
    assertTrue(handler.contains("protected Publisher<Void> on("));
    assertTrue(p.text("pom.xml").contains("axiz-adapter-inbox-r2dbc"));
  }

  @Test
  void asyncApiSnapshotPreservesTheContractUsedForGeneration() {
    ProjectRequest base =
        Fixtures.request("event-processor", Structure.EDA, Stack.REACTIVE, "aurora", List.of());
    String contract = "asyncapi: 3.0.0\ninfo: {title: Orders, version: 1.0.0}\nchannels: {}\n";
    GeneratedProject project =
        generator.generate(
            new ProjectRequest(
                base.name(),
                base.groupId(),
                base.basePackage(),
                base.description(),
                base.archetype(),
                base.structure(),
                base.stack(),
                base.persistence(),
                base.capabilities(),
                base.openApi(),
                contract,
                base.frameworkVersion(),
                base.extensions()));
    assertEquals(
        project.text("src/main/asyncapi/asyncapi.yaml"),
        project.text(".axiz/asyncapi.snapshot.yaml"));
    assertEquals(contract.strip(), project.text(".axiz/asyncapi.snapshot.yaml").strip());
    GeneratedProject withoutContract = generator.generate(base);
    assertFalse(withoutContract.files().containsKey(".axiz/asyncapi.snapshot.yaml"));
  }

  @Test
  void descriptionsAreEscapedForEveryFormat() throws Exception {
    ProjectRequest base =
        Fixtures.request(
            "business", Structure.HEXAGONAL, Stack.SERVLET, "aurora", List.of(), Extension.CQRS);
    ProjectRequest r =
        new ProjectRequest(
            base.name(),
            base.groupId(),
            base.basePackage(),
            "History: read model <R2DBC> & \"events\" -- see */ docs",
            base.archetype(),
            base.structure(),
            base.stack(),
            base.persistence(),
            base.capabilities(),
            base.openApi(),
            null,
            base.frameworkVersion());
    GeneratedProject p = generator.generate(r);
    Object chart =
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .load(p.text("deploy/helm/orders/Chart.yaml"));
    assertEquals(
        "History: read model <R2DBC> & \"events\" -- see */ docs",
        ((Map<?, ?>) chart).get("description"));
    DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(p.files().get("pom.xml")));
    String application = p.text("src/main/java/pe/axiz/orders/orders/OrdersApplication.java");
    assertFalse(application.contains("see */"));
    assertTrue(application.contains("read model &lt;R2DBC&gt; &amp;"), "doclint-safe HTML");
  }

  /** Every run of hyphens, a trailing one and markup still give well-formed POMs and Javadoc. */
  @Test
  void ordinaryPunctuationAlwaysProducesWellFormedFiles() throws Exception {
    DocumentBuilderFactory xml = DocumentBuilderFactory.newInstance();
    xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    for (String description :
        List.of(
            "Payments & collections",
            "Payments <settlement>",
            "Payments --- reconciliation",
            "Payments ---- reconciliation",
            "Payments -",
            "Payments --",
            "-- leading",
            "Ends with comment */",
            "Mail @team or {@code x}")) {
      for (String archetype : List.of("library", "business")) {
        ProjectRequest base =
            "library".equals(archetype)
                ? Fixtures.request("library", Structure.NONE, Stack.SERVLET, "none", List.of())
                : Fixtures.request(
                    "business",
                    Structure.HEXAGONAL,
                    Stack.SERVLET,
                    "aurora",
                    List.of(),
                    Extension.CQRS);
        ProjectRequest r =
            new ProjectRequest(
                base.name(),
                base.groupId(),
                base.basePackage(),
                description,
                base.archetype(),
                base.structure(),
                base.stack(),
                base.persistence(),
                base.capabilities(),
                base.openApi(),
                null,
                base.frameworkVersion());
        GeneratedProject p = generator.generate(r);
        Document pom =
            xml.newDocumentBuilder().parse(new ByteArrayInputStream(p.files().get("pom.xml")));
        String text = pom.getElementsByTagName("description").item(0).getTextContent();
        assertTrue(text.startsWith(description.replaceAll("\\.$", "")), archetype + ": " + text);
        for (Map.Entry<String, byte[]> e : p.files().entrySet()) {
          if (e.getKey().endsWith(".java")) {
            String source = new String(e.getValue(), StandardCharsets.UTF_8);
            int start = source.indexOf("/**");
            if (start >= 0) {
              String doc = source.substring(start + 3, source.indexOf("*/", start));
              assertFalse(doc.contains("<settlement>"), e.getKey());
              assertFalse(doc.contains("{@code x}") && description.contains("@team"), e.getKey());
            }
          }
        }
      }
    }
  }

  @Test
  void xmlCommentsNeverContainTwoHyphensInARow() {
    for (String s : List.of("-", "--", "---", "a--b", "a-", "a - b", "")) {
      String c = ProjectGenerator.xmlComment(s);
      assertFalse(c.contains("--"), s + " -> " + c);
      assertFalse(c.endsWith("-"), s + " -> " + c);
    }
    assertEquals("a- - -b", ProjectGenerator.xmlComment("a---b"));
  }

  @Test
  void batchRunsAsCronJobWithoutWebServer() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "batch", Structure.BATCH, Stack.SERVLET, "aurora", List.of("scheduling")));
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
                "event-processor", Structure.EDA, Stack.SERVLET, "dynamodb", List.of("inbox")));
    assertTrue(
        p.files().keySet().stream()
            .anyMatch(f -> f.endsWith("handler/ExampleCreatedHandler.java")));
    assertTrue(p.files().keySet().stream().noneMatch(f -> f.endsWith("Controller.java")));
    assertTrue(
        p.text("pom.xml").contains("axiz-adapter-inbox-dynamodb"),
        "inbox store follows the persistence");
    assertTrue(p.text("src/main/resources/application.yaml").contains("store: dynamodb"));
    assertTrue(p.text("compose.yaml").contains("kafka:"));
  }

  @Test
  void experienceComposesDownstreamCalls() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "ux", Structure.BFF, Stack.REACTIVE, "none", List.of("idempotency-redis")));
    String base = "src/main/java/pe/axiz/orders/ordersux/";
    assertTrue(p.text(base + "composition/OrdersComposition.java").contains("Mono.error("));
    assertTrue(p.text("compose.yaml").contains("valkey"));
    assertTrue(p.text("src/main/resources/application.yaml").contains("store: redis"));
  }

  @Test
  void configurationKeepsSecretsOut() {
    GeneratedProject p =
        generator.generate(
            Fixtures.request(
                "core", Structure.HEXAGONAL, Stack.SERVLET, "none", List.of("crypto", "soap")));
    String app = p.text("src/main/resources/application.yaml").toLowerCase(Locale.ROOT);
    assertFalse(app.contains("password:"));
    String values = p.text("deploy/helm/orders-core/values.yaml");
    assertTrue(values.contains("- field-key-v1"), "secrets come from the ExternalSecret");
    assertTrue(
        p.text("deploy/helm/orders-core/values-prod.yaml").contains("# axiz.crypto.kms.key-id:"));
  }

  @Test
  void platformArchetypes() {
    GeneratedProject lib =
        generator.generate(
            Fixtures.request("library", Structure.NONE, Stack.SERVLET, "none", List.of()));
    assertTrue(lib.text("pom.xml").contains("<artifactId>axiz-parent-core</artifactId>"));
    assertSeparatedDeployment(lib, "lib-deploy");
    assertTrue(lib.text(".github/workflows/ci.yaml").contains("lib-build.yaml@v2.0.0"));
    assertEquals(
        Boolean.TRUE,
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .<Map<?, ?>>load(lib.text("release-please-config.json"))
            .get("draft"));

    assertTrue(
        lib.files().containsKey("src/main/java/pe/axiz/orders/orderslibrary/OrdersLibrary.java"));
    ProjectRequest starter =
        new ProjectRequest(
            "axiz-geo",
            "pe.axiz",
            "pe.axiz.core.geo",
            "",
            "starter",
            Structure.NONE,
            Stack.SERVLET,
            "none",
            List.of(),
            null,
            null,
            "1.0.0");
    GeneratedProject s = generator.generate(starter);
    assertSeparatedDeployment(s, "lib-deploy");
    assertEquals(
        Boolean.TRUE,
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .<Map<?, ?>>load(s.text("release-please-config.json"))
            .get("draft"));

    assertTrue(
        s.files()
            .containsKey(
                "axiz-geo-spring-boot-autoconfigure/src/main/java/pe/axiz/spring/geo/GeoAutoConfiguration.java"));
    assertTrue(
        s.text(
                "axiz-geo-spring-boot-autoconfigure/src/main/java/pe/axiz/spring/geo/GeoProperties.java")
            .contains("@ConfigurationProperties(\"axiz.geo\")"));
    assertTrue(
        s.text(
                "axiz-geo-spring-boot-autoconfigure/src/main/resources/META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .contains("pe.axiz.spring.geo.GeoAutoConfiguration"));
    assertTrue(s.text("pom.xml").contains("<module>axiz-core-geo</module>"));
    assertTrue(s.text("pom.xml").contains("<artifactId>axiz-parent</artifactId>"));
    assertTrue(s.text("axiz-core-geo/pom.xml").contains("<artifactId>axiz-core-geo</artifactId>"));
    assertTrue(
        s.text("axiz-geo-spring-boot-autoconfigure/pom.xml")
            .contains("<artifactId>axiz-core-geo</artifactId>"));
    assertTrue(s.files().containsKey("axiz-core-geo/src/main/java/pe/axiz/core/geo/Geo.java"));
    assertFalse(
        s.files().keySet().stream()
            .anyMatch(path -> path.contains("spring-boot-") && path.contains("pe/axiz/core/")));
  }

  @Test
  void starterWithExplicitCorePrefixKeepsItsCapabilityNameAndSiblingSpringPackage() {
    GeneratedProject project =
        generator.generate(
            new ProjectRequest(
                "axiz-core-geo",
                "pe.axiz",
                "pe.axiz.core.geo",
                "",
                "starter",
                Structure.NONE,
                Stack.SERVLET,
                "none",
                List.of(),
                null,
                null,
                "2.0.0"));
    assertTrue(project.text("pom.xml").contains("<module>axiz-core-geo</module>"));
    assertTrue(
        project
            .text(
                "axiz-geo-spring-boot-autoconfigure/src/main/java/pe/axiz/spring/geo/GeoProperties.java")
            .contains("@ConfigurationProperties(\"axiz.geo\")"));
    assertTrue(
        project
            .text("axiz-geo-spring-boot-starter/pom.xml")
            .contains("<artifactId>axiz-geo-spring-boot-autoconfigure</artifactId>"));
    assertFalse(
        project
            .text("axiz-core-geo/src/main/java/pe/axiz/core/geo/Geo.java")
            .contains("org.springframework"));
  }

  @Test
  void zipEntriesLiveUnderTheProjectFolder() throws Exception {
    GeneratedProject p =
        generator.generate(
            Fixtures.request("business", Structure.LAYERED, Stack.SERVLET, "aurora", List.of()));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    p.writeZip(out);
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      int n = 0;
      for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        assertTrue(e.getName().startsWith("orders/"), e.getName());
        n++;
      }
      assertEquals(p.files().size(), n);
    }
  }

  private static void assertSeparatedDeployment(GeneratedProject project, String reusable) {
    assertEquals(
        Boolean.TRUE,
        new Yaml(new SafeConstructor(new LoaderOptions()))
            .<Map<?, ?>>load(project.text("release-please-config.json"))
            .get("force-tag-creation"));
    String deploy = project.text(".github/workflows/deploy.yaml");
    assertTrue(deploy.contains("workflow_dispatch"));
    assertTrue(deploy.contains("/" + reusable + ".yaml@v2.0.0"));
    assertFalse(deploy.contains("image-tag:"));
    assertFalse(deploy.contains("mvnw"));
    assertFalse(deploy.contains("/setup-maven@"));
    Map<?, ?> workflow = new Yaml(new SafeConstructor(new LoaderOptions())).load(deploy);
    Object trigger = workflow.containsKey("on") ? workflow.get("on") : workflow.get(Boolean.TRUE);
    Map<?, ?> dispatch = (Map<?, ?>) ((Map<?, ?>) trigger).get("workflow_dispatch");
    Map<?, ?> inputs = (Map<?, ?>) dispatch.get("inputs");
    Map<?, ?> job = (Map<?, ?>) ((Map<?, ?>) workflow.get("jobs")).get("deploy");
    Map<?, ?> forwarded = (Map<?, ?>) job.get("with");
    for (String key :
        List.of(
            "build-run-id",
            "build-run-attempt",
            "manifest-sha256",
            "environment",
            "previous-manifest-sha256")) {
      assertEquals(Boolean.TRUE, ((Map<?, ?>) inputs.get(key)).get("required"), key);
      String source = "service-deploy".equals(reusable) ? " || needs.automatic.outputs." + key : "";
      assertEquals("${{ inputs." + key + source + " }}", forwarded.get(key), key);
    }
    if ("service-deploy".equals(reusable)) {
      assertEquals(
          "${{ inputs.values-revision || needs.automatic.outputs.values-revision }}",
          forwarded.get("values-revision"));
      Map<?, ?> automaticTrigger = (Map<?, ?>) ((Map<?, ?>) trigger).get("workflow_run");
      assertEquals(List.of("ci"), automaticTrigger.get("workflows"));
      assertEquals(List.of("completed"), automaticTrigger.get("types"));
      Map<?, ?> automatic = (Map<?, ?>) ((Map<?, ?>) workflow.get("jobs")).get("automatic");
      assertEquals(Map.of("contents", "read", "actions", "read"), automatic.get("permissions"));
      Map<?, ?> step = (Map<?, ?>) ((List<?>) automatic.get("steps")).getFirst();
      assertEquals("axiz/axiz-workflows/actions/resolve-service-build@v2.0.0", step.get("uses"));
      assertEquals("dev", ((Map<?, ?>) step.get("with")).get("environment"));
      assertTrue(job.get("if").toString().contains("needs.automatic.result == 'success'"));
    } else assertEquals("${{ inputs.destination }}", forwarded.get("destination"));
  }
}
