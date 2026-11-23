package pe.axiz.adapter.observability.aws;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.observation.Observation;
import io.micrometer.observation.Observation.Scope;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.SenderContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;
import software.amazon.awssdk.core.ClientType;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * Interceptor del AWS SDK v2 que envuelve cada llamada (DynamoDB, SQS, SNS, S3, KMS, Secrets
 * Manager, etc.) en una observación de Micrometer: un span CLIENT con servicio y operación y el
 * timer {@code axiz.aws.sdk}. Funciona en imágenes nativas (sin agente ni manipulación de
 * bytecode). Se registra en cada builder de cliente mediante {@code overrideConfiguration}.
 */
public final class ObservationExecutionInterceptor implements ExecutionInterceptor {

  /** Observación de una llamada y el scope que abrió, guardados juntos. */
  private record Active(
      Observation observation, @Nullable Scope scope, Map<String, String> propagation) {}

  private static final ExecutionAttribute<Active> ACTIVE =
      new ExecutionAttribute<>("axiz.observation");

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "ObservationRegistry is an injected collaborator whose identity and lifecycle must "
              + "be shared, not copied.")
  private final ObservationRegistry registry;

  public ObservationExecutionInterceptor(ObservationRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
    String service = String.valueOf(attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME));
    String operation =
        String.valueOf(attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME));
    Map<String, String> propagation = new LinkedHashMap<>();
    SenderContext<Map<String, String>> sender =
        new SenderContext<>(
            (carrier, name, value) -> {
              if (carrier != null) carrier.put(name, value);
            },
            Kind.CLIENT);
    sender.setCarrier(propagation);
    sender.setRemoteServiceName(service);
    Observation observation =
        Observation.createNotStarted(AxizObservations.AWS_SDK, () -> sender, registry)
            .contextualName(service + "." + operation)
            .lowCardinalityKeyValue(AxizAttributes.AWS_SERVICE, service)
            .lowCardinalityKeyValue(AxizAttributes.AWS_OPERATION, operation)
            .start();
    // El SDK identifica ambos tipos de cliente, incluidos los clientes configurados fuera de las
    // fábricas de Axiz. Los clientes nativos terminan en otro hilo; un scope nunca debe cruzar esa
    // frontera de hilos.
    boolean synchronous =
        attributes.getAttribute(SdkExecutionAttribute.CLIENT_TYPE) != ClientType.ASYNC;
    attributes.putAttribute(
        ACTIVE,
        new Active(
            observation, synchronous ? observation.openScope() : null, Map.copyOf(propagation)));
  }

  /**
   * Agrega la propagación de trazas antes de la firma SigV4, de forma independiente para cada
   * petición nativa.
   */
  @Override
  public SdkHttpRequest modifyHttpRequest(
      Context.ModifyHttpRequest context, ExecutionAttributes attributes) {
    Active active = attributes.getAttribute(ACTIVE);
    if (active == null || active.propagation().isEmpty()) return context.httpRequest();
    var request = context.httpRequest().toBuilder();
    active.propagation().forEach(request::putHeader);
    return request.build();
  }

  @Override
  public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
    Active active = attributes.getAttribute(ACTIVE);
    if (active != null) {
      Observation observation = active.observation();
      context
          .httpResponse()
          .firstMatchingHeader("x-amzn-RequestId")
          .or(() -> context.httpResponse().firstMatchingHeader("x-amz-request-id"))
          .ifPresent(id -> observation.highCardinalityKeyValue(AxizAttributes.AWS_REQUEST_ID, id));
      observation.lowCardinalityKeyValue("outcome", "success");
      close(active);
    }
  }

  @Override
  public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
    Active active = attributes.getAttribute(ACTIVE);
    if (active != null) {
      active.observation().lowCardinalityKeyValue("outcome", "error");
      active.observation().error(context.exception());
      close(active);
    }
  }

  private static void close(Active active) {
    Observation.Scope scope = active.scope();
    if (scope != null) scope.close();
    active.observation().stop();
  }
}
