package pe.axiz.spring.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.kit.jpa.AuditListener;
import pe.axiz.kit.jpa.AuditorProvider;

class JpaTest {

  private final AxizJpaEnvironmentPostProcessor epp = new AxizJpaEnvironmentPostProcessor();

  @Test
  void sqlAndConstraintDiagnosticsCannotLeakValuesInDeployedEnvironments() {
    var defaults = new MockEnvironment();
    epp.postProcessEnvironment(defaults, new SpringApplication());
    assertEquals("false", defaults.getProperty("spring.jpa.show-sql"));
    assertEquals("OFF", defaults.getProperty("logging.level.org.hibernate.orm.jdbc.error"));
    for (String property :
        java.util.List.of(
            "spring.jpa.show-sql",
            "spring.jpa.properties.hibernate.show_sql",
            "logging.level.org.hibernate.SQL",
            "logging.level.org.hibernate.orm.jdbc",
            "logging.level.org.hibernate.orm.jdbc.error",
            "logging.level.org.hibernate.orm.jdbc.bind")) {
      String value = property.startsWith("logging.") ? "ERROR" : "true";
      var deployed = new MockEnvironment().withProperty(property, value);
      assertThrows(
          IllegalStateException.class,
          () -> epp.postProcessEnvironment(deployed, new SpringApplication()),
          property);
      var synthetic =
          new MockEnvironment()
              .withProperty(property, value)
              .withProperty("axiz.config.environment", "test");
      epp.postProcessEnvironment(synthetic, new SpringApplication());
      assertEquals(value, synthetic.getProperty(property));
    }
  }

  @Test
  void schemaChangesOnlyLocally() {
    MockEnvironment prod =
        new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
    assertThrows(
        IllegalStateException.class,
        () -> epp.postProcessEnvironment(prod, new SpringApplication()));
    MockEnvironment local =
        new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
    local.setActiveProfiles("local");
    local.setProperty("axiz.config.environment", "local");
    epp.postProcessEnvironment(local, new SpringApplication());
    MockEnvironment defaults = new MockEnvironment();
    epp.postProcessEnvironment(defaults, new SpringApplication());
    assertEquals("validate", defaults.getProperty("spring.jpa.hibernate.ddl-auto"));
    assertEquals("false", defaults.getProperty("spring.jpa.open-in-view"));
  }

  @Test
  void auditorFromCaller() {
    RequestAuditorProvider p = new RequestAuditorProvider();
    assertTrue(p.currentAuditor().isEmpty());
    try (RequestContext.Scope s =
        RequestContext.open(
            RequestMetadata.builder("corr-12345678", "req-12345678")
                .clientId("app")
                .subject("user-1")
                .build())) {
      assertEquals("user-1", p.currentAuditor().orElseThrow());
    }
  }

  @Test
  void onlyExplicitSingleProcessEnvironmentsAllowSchemaChanges() {
    MockEnvironment lookalike =
        new MockEnvironment()
            .withProperty("spring.jpa.hibernate.ddl-auto", "create")
            .withProperty("spring.profiles.active", "attestation, localstack-prod");
    assertThrows(
        IllegalStateException.class,
        () -> epp.postProcessEnvironment(lookalike, new SpringApplication()));
    MockEnvironment test =
        new MockEnvironment()
            .withProperty("spring.jpa.hibernate.ddl-auto", "create-drop")
            .withProperty("spring.profiles.active", "cloud, test")
            .withProperty("axiz.config.environment", "test");
    epp.postProcessEnvironment(test, new SpringApplication());
    MockEnvironment none =
        new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "none");
    epp.postProcessEnvironment(none, new SpringApplication());
    assertEquals("none", none.getProperty("spring.jpa.hibernate.ddl-auto"));
  }

  @Test
  void machineCallersAreAuditedByClientId() {
    RequestAuditorProvider p = new RequestAuditorProvider();
    try (RequestContext.Scope s =
        RequestContext.open(
            RequestMetadata.builder("corr-12345678", "req-12345678")
                .clientId("batch-app")
                .build())) {
      assertEquals("batch-app", p.currentAuditor().orElseThrow());
    }
    try (RequestContext.Scope s =
        RequestContext.open(RequestMetadata.builder("corr-12345678", "req-12345678").build())) {
      assertTrue(p.currentAuditor().isEmpty());
    }
  }

  @Test
  void blankIdentitiesAreNotRecordedAsAuditors() {
    RequestAuditorProvider provider = new RequestAuditorProvider();
    try (RequestContext.Scope scope =
        RequestContext.open(
            RequestMetadata.builder("corr-12345678", "req-12345678")
                .subject(" ")
                .clientId("batch-app")
                .build())) {
      assertEquals("batch-app", provider.currentAuditor().orElseThrow());
    }
    try (RequestContext.Scope scope =
        RequestContext.open(
            RequestMetadata.builder("corr-12345678", "req-12345678")
                .subject(" ")
                .clientId("\t")
                .build())) {
      assertTrue(provider.currentAuditor().isEmpty());
    }
  }

  @Test
  void theAuditorIsPublishedToTheEntityListener() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizJpaAutoConfiguration.class))
        .run(
            ctx -> {
              assertThat(ctx)
                  .hasSingleBean(AuditorProvider.class)
                  .hasSingleBean(AuditListener.class);
              assertInstanceOf(RequestAuditorProvider.class, ctx.getBean(AuditorProvider.class));
            });
    AuditorProvider fixed = () -> Optional.of("migration");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizJpaAutoConfiguration.class))
        .withBean(AuditorProvider.class, () -> fixed)
        .run(ctx -> assertSame(fixed, ctx.getBean(AuditorProvider.class)));
  }
}
