package pe.axiz.demo.providers;

import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Selects an actual web application type, including Spring's conditional configuration. */
final class RecipeStacks {
  private RecipeStacks() {}

  static AbstractApplicationContextRunner<?, ?, ?> runner(String stack) {
    return switch (stack) {
      case "servlet" -> new WebApplicationContextRunner();
      case "reactive" -> new ReactiveWebApplicationContextRunner();
      default -> throw new IllegalArgumentException("Unknown recipe stack");
    };
  }
}
