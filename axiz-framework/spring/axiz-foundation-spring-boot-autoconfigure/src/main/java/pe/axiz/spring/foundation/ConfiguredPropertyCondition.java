package pe.axiz.spring.foundation;

import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class ConfiguredPropertyCondition extends SpringBootCondition {
  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    var attributes =
        Objects.requireNonNull(
            metadata.getAnnotationAttributes(ConditionalOnConfiguredProperty.class.getName()));
    String key = (String) Objects.requireNonNull(attributes.get("value"));
    return context.getEnvironment().containsProperty(key)
        ? ConditionOutcome.match("Configured property: " + key)
        : ConditionOutcome.noMatch("Missing property: " + key);
  }
}
