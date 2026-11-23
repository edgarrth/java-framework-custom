package pe.axiz.spring.test;

import java.util.Arrays;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Instala la detección de bloqueos habilitada antes de que cualquier prueba pueda iniciar un
 * scheduler. De lo contrario, retransformar un executor del JDK que ya está esperando puede
 * invalidar su frame de bloqueo activo en Java 25. Los planes exclusivamente servlet no instalan
 * BlockHound.
 */
public final class BlockHoundTestExecutionListener implements TestExecutionListener {
  @Override
  public void testPlanExecutionStarted(TestPlan plan) {
    for (var root : plan.getRoots()) {
      if (requiresBlockHound(root)
          || plan.getDescendants(root).stream().anyMatch(this::requiresBlockHound)) {
        BlockHoundExtension.install();
        return;
      }
    }
  }

  private boolean requiresBlockHound(TestIdentifier test) {
    var source = test.getSource().orElse(null);
    Class<?> type;
    if (source instanceof ClassSource classes) type = classes.getJavaClass();
    else if (source instanceof MethodSource method) type = method.getJavaClass();
    else return false;
    return AnnotationSupport.findRepeatableAnnotations(type, ExtendWith.class).stream()
        .flatMap(extension -> Arrays.stream(extension.value()))
        .anyMatch(BlockHoundExtension.class::isAssignableFrom);
  }
}
