package pe.axiz.spring.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;

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
}
