package pe.axiz.framework.batch.spring;

import org.springframework.batch.core.job.AbstractJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Registers {@link ObservedJobListener} on every job bean. */
public class ObservedJobListenerPostProcessor implements BeanPostProcessor {

  private final ObjectProvider<ObservedJobListener> listener;

  public ObservedJobListenerPostProcessor(ObjectProvider<ObservedJobListener> listener) {
    this.listener = listener;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof AbstractJob job) {
      ObservedJobListener l = listener.getIfAvailable();
      if (l != null) {
        job.registerJobExecutionListener(l);
      }
    }
    return bean;
  }
}
