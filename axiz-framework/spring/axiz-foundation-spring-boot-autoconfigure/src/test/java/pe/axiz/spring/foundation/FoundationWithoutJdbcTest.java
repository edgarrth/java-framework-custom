package pe.axiz.spring.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class FoundationWithoutJdbcTest {
  @Test
  void servletFoundationDoesNotRequireTheOptionalJdbcKit() {
    new WebApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader("pe.axiz.kit.jdbc"))
        .withConfiguration(AutoConfigurations.of(AxizFoundationAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(Clock.class));
  }

  @Test
  void reactiveFoundationDoesNotRequireTheOptionalJdbcKit() {
    new ReactiveWebApplicationContextRunner()
        .withClassLoader(new FilteredClassLoader("pe.axiz.kit.jdbc"))
        .withConfiguration(AutoConfigurations.of(AxizFoundationAutoConfiguration.class))
        .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(Clock.class));
  }
}
