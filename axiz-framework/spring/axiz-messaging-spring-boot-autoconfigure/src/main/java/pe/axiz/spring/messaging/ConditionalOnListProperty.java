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
 * Coincide cuando una propiedad de lista tiene al menos un elemento no vacío, sin importar cómo se
 * escriba: lista YAML ({@code topics: [payments]} o {@code - payments}), claves indexadas ({@code
 * topics[0]=...}), valor separado por comas o variable de entorno. Los placeholders {@code
 * ${prop:}} y {@code @ConditionalOnProperty} solo ven la forma separada por comas, por lo que para
 * ellos una lista YAML parece vacía.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ConditionalOnListProperty.OnListPropertyCondition.class)
public @interface ConditionalOnListProperty {

  /** Nombre de la propiedad de lista. */
  String value();

  /** Enlaza la propiedad como lista y comprueba que no esté vacía. */
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

    /** Elementos no vacíos de la propiedad de lista, en cualquiera de sus formas aceptadas. */
    static List<String> values(Environment env, String name) {
      return Binder.get(env).bind(name, Bindable.listOf(String.class)).orElseGet(List::of).stream()
          .filter(v -> v != null && !v.isBlank())
          .toList();
    }
  }
}
