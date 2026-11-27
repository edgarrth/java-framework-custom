package pe.axiz.demo.providers;

import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Selecciona un tipo real de aplicación web, incluida la configuración condicional de Spring. */
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
