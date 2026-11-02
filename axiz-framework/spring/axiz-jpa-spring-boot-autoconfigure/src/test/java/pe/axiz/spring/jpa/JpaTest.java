package pe.axiz.spring.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.kit.jpa.AuditorProvider;

class JpaTest {

  private final AxizJpaEnvironmentPostProcessor epp = new AxizJpaEnvironmentPostProcessor();

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
    epp.postProcessEnvironment(local, new SpringApplication());
    MockEnvironment defaults = new MockEnvironment();
    epp.postProcessEnvironment(defaults, new SpringApplication());
    assertEquals("validate", defaults.getProperty("spring.jpa.hibernate.ddl-auto"));
    assertEquals("false", defaults.getProperty("spring.jpa.open-in-view"));
  }

  @Test
  void awsWrapperPlugins() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
            .withProperty("axiz.jpa.aws-wrapper.iam", "true")
            .withProperty("axiz.jpa.aws-wrapper.read-write-splitting", "true");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "readWriteSplitting,failover2,efm2,iam",
        env.getProperty("spring.datasource.hikari.data-source-properties.wrapperPlugins"));
  }

  @Test
  void auditorFromCaller() {
    RequestAuditorProvider p = new RequestAuditorProvider();
    assertEquals("system", p.currentAuditor().orElseThrow());
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
  void onlyWholeLocalOrTestProfileNamesAllowSchemaChanges() {
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
            .withProperty("spring.profiles.active", "cloud, test");
    epp.postProcessEnvironment(test, new SpringApplication());
    MockEnvironment none =
        new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "none");
    epp.postProcessEnvironment(none, new SpringApplication());
    assertEquals("none", none.getProperty("spring.jpa.hibernate.ddl-auto"));
  }

  @Test
  void theAwsWrapperIsOptInAndKeepsServiceSettings() {
    MockEnvironment plain = new MockEnvironment();
    epp.postProcessEnvironment(plain, new SpringApplication());
    assertNull(plain.getProperty("spring.datasource.driver-class-name"));

    MockEnvironment aurora =
        new MockEnvironment()
            .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
            .withProperty("spring.datasource.hikari.maximum-pool-size", "8");
    epp.postProcessEnvironment(aurora, new SpringApplication());
    assertEquals(
        "software.amazon.jdbc.Driver", aurora.getProperty("spring.datasource.driver-class-name"));
    assertEquals(
        "failover2,efm2",
        aurora.getProperty("spring.datasource.hikari.data-source-properties.wrapperPlugins"));
    assertEquals("8", aurora.getProperty("spring.datasource.hikari.maximum-pool-size"));
    assertEquals(Ordered.LOWEST_PRECEDENCE, epp.getOrder());
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
      assertEquals("system", p.currentAuditor().orElseThrow());
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
                  .hasSingleBean(AuditingRegistrar.class);
              assertInstanceOf(RequestAuditorProvider.class, ctx.getBean(AuditorProvider.class));
            });
    AuditorProvider fixed = () -> java.util.Optional.of("migration");
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizJpaAutoConfiguration.class))
        .withBean(AuditorProvider.class, () -> fixed)
        .run(ctx -> assertSame(fixed, ctx.getBean(AuditorProvider.class)));
  }
}
