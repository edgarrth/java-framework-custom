package pe.axiz.quality.archunit.sample.guarded.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import pe.axiz.core.commons.ReviewedState;

public class Singletons {
  @Service
  public static class Unreviewed {
    final List<String> values;

    public Unreviewed(List<String> values) {
      this.values = values;
    }
  }

  @Service
  public static class Reviewed {
    @ReviewedState(reason = "Snapshot owned by this bean")
    final List<String> values;

    public Reviewed(List<String> values) {
      this.values = List.copyOf(values);
    }
  }

  @Service
  public static class Empty {
    @ReviewedState(reason = " ")
    final AtomicInteger value = new AtomicInteger();
  }

  public static class FactoryBean {
    final AtomicInteger counter = new AtomicInteger();
  }

  @Bean
  public FactoryBean factoryBean() {
    return new FactoryBean();
  }
}
