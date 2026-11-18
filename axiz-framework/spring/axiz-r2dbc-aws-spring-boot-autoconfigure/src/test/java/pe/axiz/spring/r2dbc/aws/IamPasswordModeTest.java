package pe.axiz.spring.r2dbc.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class IamPasswordModeTest {
  @Test
  void dynamicPasswordsAreDefaultedOnlyForIamAndNeverOverrideExplicitPolicy() {
    var processor = new AxizR2dbcAwsEnvironmentPostProcessor();
    var application = new SpringApplication(IamPasswordModeTest.class);
    var disabled = new MockEnvironment();
    processor.postProcessEnvironment(disabled, application);
    assertThat(disabled.getProperty("axiz.r2dbc.password-mode")).isNull();
    var enabled = new MockEnvironment().withProperty("axiz.r2dbc.iam.enabled", "true");
    processor.postProcessEnvironment(enabled, application);
    assertThat(enabled.getProperty("axiz.r2dbc.password-mode")).isEqualTo("dynamic");
    var explicit =
        new MockEnvironment()
            .withProperty("axiz.r2dbc.iam.enabled", "true")
            .withProperty("axiz.r2dbc.password-mode", "fixed");
    processor.postProcessEnvironment(explicit, application);
    assertThat(explicit.getProperty("axiz.r2dbc.password-mode")).isEqualTo("fixed");
    assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 1);
  }
}
