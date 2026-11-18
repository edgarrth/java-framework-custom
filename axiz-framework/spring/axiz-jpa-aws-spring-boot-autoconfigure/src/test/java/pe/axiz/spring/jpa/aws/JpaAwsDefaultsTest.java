package pe.axiz.spring.jpa.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class JpaAwsDefaultsTest {
  private final AxizJpaAwsEnvironmentPostProcessor epp = new AxizJpaAwsEnvironmentPostProcessor();

  @Test
  void awsWrapperPlugins() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
            .withProperty("axiz.jpa.aws-wrapper.iam", "true")
            .withProperty("axiz.jpa.aws-wrapper.read-write-splitting", "true");
    epp.postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "axiz-jpa-iam-split",
        env.getProperty("spring.datasource.hikari.data-source-properties.wrapperProfileName"));
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
}
