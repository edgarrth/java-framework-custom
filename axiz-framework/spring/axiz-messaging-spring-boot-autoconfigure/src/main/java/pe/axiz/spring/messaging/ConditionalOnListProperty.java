package pe.axiz.spring.messaging;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when a list property has at least one non-blank element, however it is written: YAML list
 * ({@code topics: [payments]} or {@code - payments}), indexed keys ({@code topics[0]=...}),
 * comma-separated value or environment variable. {@code ${prop:}} placeholders and
 * {@code @ConditionalOnProperty} only see the comma-separated form, so a YAML list looks empty to
 * them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ConditionalOnListProperty.OnListPropertyCondition.class)
@interface ConditionalOnListProperty {

  /** Name of the list property. */
  String value();

  /** Binds the property as a list and checks that it is not empty. */
  final class OnListPropertyCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      Map<String, @Nullable Object> attributes =
          Objects.requireNonNull(
              metadata.getAnnotationAttributes(ConditionalOnListProperty.class.getName()));
      String name = (String) Objects.requireNonNull(attributes.get("value"));
      List<String> values = values(context.getEnvironment(), name);
      return values.isEmpty()
          ? ConditionOutcome.noMatch(name + " is empty")
          : ConditionOutcome.match(name + " has " + values.size() + " element(s)");
    }

    /** Non-blank elements of the list property, in any of its accepted forms. */
    static List<String> values(Environment env, String name) {
      return Binder.get(env).bind(name, Bindable.listOf(String.class)).orElseGet(List::of).stream()
          .filter(v -> v != null && !v.isBlank())
          .toList();
    }
  }
}
