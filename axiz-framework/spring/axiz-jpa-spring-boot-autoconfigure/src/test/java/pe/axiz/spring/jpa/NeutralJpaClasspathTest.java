package pe.axiz.spring.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.kit.jpa.AuditListener;

class NeutralJpaClasspathTest {
  @Test
  void jpaAuditingWorksWithNoAwsWrapperOrSdkOnTheActualClasspath() {
    for (String name :
        List.of(
            "software.amazon.jdbc.Driver",
            "software.amazon.awssdk.auth.credentials.AwsCredentialsProvider")) {
      assertThatThrownBy(() -> Class.forName(name)).isInstanceOf(ClassNotFoundException.class);
    }
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizJpaAutoConfiguration.class))
        .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AuditListener.class));
  }
}
