package pe.axiz.spring.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.core.audit.AuditEvent;
import pe.axiz.core.audit.Auditable;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;

class AuditTest {

  public static class Orders {
    @Auditable(action = "order.create", resource = "order", resourceIdArgument = "id")
    public String create(String id, String password) {
      if (id.equals("bad")) {
        throw new BusinessException(CoreErrors.CONFLICT, "duplicated");
      }
      return id;
    }
  }

  @Test
  void auditableRecordsSanitizedEventsWithRequestContext() {
    List<AuditEvent> events = new CopyOnWriteArrayList<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAuditAutoConfiguration.class))
        .withPropertyValues("spring.application.name=orders", "axiz.audit.mode=SYNC")
        .withBean(AuditSink.class, () -> new AuditSink("memory", events::add))
        .run(
            ctx -> {
              ProxyFactory pf = new ProxyFactory(new Orders());
              pf.setProxyTargetClass(true);
              pf.addAdvisor(ctx.getBean("axizAuditableAdvisor", Advisor.class));
              Orders orders = (Orders) pf.getProxy();
              RequestMetadata m =
                  RequestMetadata.builder("corr-12345678", "req-12345678")
                      .subject("user-1")
                      .channel("web")
                      .build();
              try (RequestContext.Scope ignored = RequestContext.open(m)) {
                orders.create("o-1", "s3cr3t");
                assertThrows(BusinessException.class, () -> orders.create("bad", "x"));
              }
            });
    assertEquals(2, events.size());
    AuditEvent ok = events.get(0);
    assertEquals("orders", ok.service());
    assertEquals("o-1", ok.resourceId());
    assertEquals("user-1", ok.actor().subject());
    assertEquals("corr-12345678", ok.correlationId());
    assertFalse(ok.details().toString().contains("s3cr3t"));
    assertEquals(AuditEvent.Outcome.FAILURE, events.get(1).outcome());
    assertEquals("AXZ-CORE-0409", events.get(1).errorCode());
  }

  @Test
  void httpRulesUsePatterns() {
    HttpAuditRules rules =
        new HttpAuditRules(
            new AxizAuditProperties.Http(
                true, List.of("POST"), List.of("/payments/**"), List.of("/actuator/**")));
    assertTrue(rules.audited("post", "/payments/1"));
    assertFalse(rules.audited("GET", "/payments/1"));
    assertFalse(rules.audited("POST", "/other"));
  }
}
