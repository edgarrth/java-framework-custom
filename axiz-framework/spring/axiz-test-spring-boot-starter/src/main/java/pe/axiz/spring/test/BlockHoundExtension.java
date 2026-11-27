package pe.axiz.spring.test;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;

/**
 * Hace fallar las pruebas reactivas que bloquean un hilo del event loop. Obligatorio para servicios
 * reactivos: {@code @ExtendWith(BlockHoundExtension.class)}.
 */
public class BlockHoundExtension implements BeforeAllCallback {

  private static boolean installed;
  private static final Queue<BlockingCall> DETECTED = new ConcurrentLinkedQueue<>();
  private static final AtomicInteger RECORDED = new AtomicInteger();

  /**
   * Registros que cargan sus proveedores con {@link java.util.ServiceLoader} en su inicialización
   * estática, leyendo los JAR del classpath. Es un arranque único (en producción ocurre al iniciar
   * la aplicación), así que se inicializan antes de instalar BlockHound en lugar de permitir
   * lecturas de disco en los hilos reactivos.
   */
  private static final List<String> BOOTSTRAP_REGISTRIES =
      List.of("io.micrometer.context.ContextRegistry");

  /**
   * El diagnóstico acotado conserva el punto de bloqueo original incluso cuando un driver enmascara
   * el error.
   */
  public record BlockingCall(String thread, String method, List<StackTraceElement> stack) {
    public BlockingCall {
      stack = List.copyOf(stack);
    }
  }

  public static List<BlockingCall> detectedBlockingCalls() {
    return List.copyOf(DETECTED);
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    install();
  }

  static synchronized void install() {
    if (!installed) {
      initializeBootstrapRegistries();
      BlockHound.builder()
          .loadIntegrations()
          .nonBlockingThreadPredicate(
              existing ->
                  existing.or(
                      thread ->
                          thread.getName().startsWith("axiz-aws-future-")
                              || thread.getName().startsWith("aws-java-sdk-NettyEventLoop")
                              || thread.getName().startsWith("lettuce-nioEventLoop-")
                              || thread.getName().startsWith("lettuce-epollEventLoop-")
                              || thread.getName().startsWith("lettuce-kqueueEventLoop-")
                              || thread.getName().startsWith("lettuce-eventExecutorLoop-")))
          // Solo se permite el límite de logging asíncrono, nunca los appenders síncronos.
          .allowBlockingCallsInside("ch.qos.logback.core.AsyncAppenderBase", "append")
          // Un worker inactivo de un executor debe esperar trabajo. Este límite exacto de
          // planificación del JDK no cubre callbacks ni permite operaciones bloqueantes de colas en
          // el código de la aplicación.
          .allowBlockingCallsInside("java.util.concurrent.ThreadPoolExecutor", "getTask")
          // AWS Netty cierra sus pools de canales en GlobalEventExecutor durante el apagado del
          // cliente. Solo se permite este límite de limpieza del transporte; client.close() del
          // lado del invocante y los callbacks de la aplicación no heredan esta excepción.
          .allowBlockingCallsInside("io.netty.channel.pool.SimpleChannelPool", "close")
          .blockingMethodCallback(
              method -> {
                var failure = new BlockingOperationError(method);
                if (RECORDED.getAndUpdate(count -> count < 32 ? count + 1 : count) < 32)
                  DETECTED.add(
                      new BlockingCall(
                          Thread.currentThread().getName(),
                          method.toString(),
                          List.of(failure.getStackTrace())));
                throw failure;
              })
          .install();
      installed = true;
    }
  }

  private static void initializeBootstrapRegistries() {
    ClassLoader loader = BlockHoundExtension.class.getClassLoader();
    for (String registry : BOOTSTRAP_REGISTRIES) {
      try {
        Class.forName(registry, true, loader);
      } catch (ClassNotFoundException absent) {
        // El registro no está en el classpath de esta prueba; no hay nada que inicializar.
      }
    }
  }
}
