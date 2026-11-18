package pe.axiz.spring.audit;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import pe.axiz.core.commons.Environments;

/** Startup policy for framework-owned publishers; custom publishers own their delivery policy. */
final class AuditModeSelection {
  private AuditModeSelection() {}

  static boolean development(Environment environment) {
    return Environments.allowsInMemoryState(environment.getProperty("axiz.config.environment"));
  }

  static boolean managed(ConfigurableListableBeanFactory beans) {
    return owned(beans, "axizAuditPublisher", "pe.axiz.spring.audit.AxizAuditAutoConfiguration")
        || (owned(
                beans,
                "axizAsyncAuditEventPublisher",
                "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration")
            && owned(
                beans,
                "axizReactiveAuditPublisher",
                "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration"));
  }

  static boolean strictManaged(ConfigurableListableBeanFactory beans) {
    return owned(
            beans,
            "axizStrictAuditPublisher",
            "pe.axiz.spring.audit.AxizStrictAuditAutoConfiguration")
        || owned(
            beans,
            "axizAsyncStrictAuditPublisher",
            "pe.axiz.spring.audit.AxizStrictAuditAutoConfiguration$NativeConfiguration");
  }

  private static boolean owned(ConfigurableListableBeanFactory beans, String name, String owner) {
    if (!beans.containsBeanDefinition(name)) return false;
    String factory = beans.getMergedBeanDefinition(name).getFactoryBeanName();
    if (factory == null) return false;
    Class<?> type = beans.getType(factory);
    return type != null && type.getName().equals(owner);
  }

  static void requireExplicit(Environment environment) {
    if (development(environment) || !environment.getProperty("axiz.audit.mode", "").isBlank())
      return;
    throw new IllegalStateException(
        "Declare axiz.audit.mode (ASYNC, DURABLE or SYNC) outside local/test/ci: "
            + "ASYNC uses an in-memory queue and cannot survive a process crash");
  }
}
