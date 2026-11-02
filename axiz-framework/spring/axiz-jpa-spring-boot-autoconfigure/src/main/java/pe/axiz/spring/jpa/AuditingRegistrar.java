package pe.axiz.spring.jpa;

import java.time.Clock;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import pe.axiz.kit.jpa.AuditingRegistry;
import pe.axiz.kit.jpa.AuditorProvider;

/** Publishes the auditor and clock to the JPA entity listener, which JPA instantiates itself. */
public class AuditingRegistrar implements InitializingBean, DisposableBean {

  private final AuditorProvider auditor;
  private final Clock clock;

  public AuditingRegistrar(AuditorProvider auditor, Clock clock) {
    this.auditor = auditor;
    this.clock = clock;
  }

  @Override
  public void afterPropertiesSet() {
    AuditingRegistry.register(auditor, clock);
  }

  @Override
  public void destroy() {
    AuditingRegistry.reset();
  }
}
