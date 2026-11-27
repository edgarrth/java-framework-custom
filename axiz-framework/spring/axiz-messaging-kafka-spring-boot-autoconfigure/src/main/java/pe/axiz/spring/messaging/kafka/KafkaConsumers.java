package pe.axiz.spring.messaging.kafka;

import java.util.List;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/** Inicia los contenedores de listeners cuando el contexto está listo y los detiene al apagarse. */
public class KafkaConsumers implements SmartLifecycle {

  private final List<ConcurrentMessageListenerContainer<String, byte[]>> containers;
  private volatile boolean running;

  public KafkaConsumers(List<ConcurrentMessageListenerContainer<String, byte[]>> containers) {
    this.containers = List.copyOf(containers);
  }

  @Override
  public void start() {
    containers.forEach(ConcurrentMessageListenerContainer::start);
    running = true;
  }

  @Override
  public void stop() {
    containers.forEach(ConcurrentMessageListenerContainer::stop);
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
