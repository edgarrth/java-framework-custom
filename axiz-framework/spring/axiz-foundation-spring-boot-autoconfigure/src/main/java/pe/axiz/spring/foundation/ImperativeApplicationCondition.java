package pe.axiz.spring.foundation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;

final class ImperativeApplicationCondition extends NoneNestedConditions {
  ImperativeApplicationCondition() {
    super(ConfigurationPhase.REGISTER_BEAN);
  }

  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
  static class ReactiveApplication {}
}
