package pe.axiz.spring.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.core.logging.LogOperation;
import pe.axiz.core.logging.NoLog;

@ExtendWith(OutputCaptureExtension.class)
class LoggingTest {

  public static class Transfers {
    @LogOperation("transfer")
    public String transfer(String account, @NoLog String pin) {
      return "ok:" + account;
    }

    @LogOperation
    public void boom() {
      throw new IllegalStateException("db down");
    }
  }

  private Transfers proxied(Advisor advisor) {
    ProxyFactory pf = new ProxyFactory(new Transfers());
    pf.setProxyTargetClass(true);
    pf.addAdvisor(advisor);
    return (Transfers) pf.getProxy();
  }

  @Test
  void logsEntryAndExitWithoutExcludedArguments(CapturedOutput output) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizLoggingAutoConfiguration.class))
        .run(
            ctx -> {
              Transfers t = proxied(ctx.getBean("axizLogOperationAdvisor", Advisor.class));
              assertEquals("ok:123", t.transfer("123", "9876"));
              assertThrows(IllegalStateException.class, t::boom);
            });
    assertTrue(output.getOut().contains("operation=transfer phase=start"));
    assertTrue(output.getOut().contains("operation=transfer phase=end outcome=success"));
    assertFalse(output.getOut().contains("9876"));
    assertTrue(output.getOut().contains("operation=Transfers.boom phase=end outcome=error"));
  }

  @Test
  void jsonOnlyInsideTheClusterByDefault() {
    MockEnvironment laptop = new MockEnvironment();
    new AxizLoggingEnvironmentPostProcessor()
        .postProcessEnvironment(laptop, new SpringApplication());
    assertEquals(null, laptop.getProperty("logging.structured.format.console"));

    MockEnvironment pod =
        new MockEnvironment()
            .withProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1")
            .withProperty("spring.application.name", "payments");
    new AxizLoggingEnvironmentPostProcessor().postProcessEnvironment(pod, new SpringApplication());
    assertEquals("logstash", pod.getProperty("logging.structured.format.console"));
    assertEquals("trace_id", pod.getProperty("logging.structured.json.rename.traceId"));
    assertEquals("payments", pod.getProperty("logging.structured.json.add.service"));
  }
}
