package pe.axiz.spring.audit;

import java.util.List;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Startup policy for framework-owned publishers; custom publishers own their delivery policy. */
final class AuditModeSelection {
  private AuditModeSelection() {}

  static boolean development(Environment environment) {
    return environment.acceptsProfiles(Profiles.of("local", "test"))
        || List.of("local", "test")
            .contains(environment.getProperty("axiz.config.environment", ""));
  }

  static boolean managed(ConfigurableListableBeanFactory beans) {
    return owned(beans, "axizAuditPublisher", "pe.axiz.spring.audit.AxizAuditAutoConfiguration")
        || owned(
                beans,
                "axizAsyncAuditEventPublisher",
                "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration")
            && owned(
                beans,
                "axizReactiveAuditPublisher",
                "pe.axiz.spring.audit.AxizNativeAuditAutoConfiguration");
  }

  private static boolean owned(ConfigurableListableBeanFactory beans, String name, String owner) {
    if (!beans.containsBeanDefinition(name)) return false;
    String factory = beans.getMergedBeanDefinition(name).getFactoryBeanName();
    if (factory == null) return false;
    Class<?> type = beans.getType(factory);
    return type != null && type.getName().equals(owner);
  }

  static void rejectRemovedSelectors(Environment environment) {
    for (String key : List.of("axiz.audit.async.enabled", "axiz.audit.durable.enabled"))
      if (environment.containsProperty(key))
        throw new IllegalStateException(
            key + " was removed; use only axiz.audit.mode (ASYNC, DURABLE or SYNC)");
  }

  static void requireExplicit(Environment environment) {
    if (development(environment) || !environment.getProperty("axiz.audit.mode", "").isBlank())
      return;
    throw new IllegalStateException(
        "Declare axiz.audit.mode (ASYNC, DURABLE or SYNC) outside local/test: "
            + "ASYNC uses an in-memory queue and cannot survive a process crash");
  }
}
