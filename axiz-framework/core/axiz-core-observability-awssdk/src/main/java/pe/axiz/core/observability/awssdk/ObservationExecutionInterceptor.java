package pe.axiz.core.observability.awssdk;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import pe.axiz.core.observability.AxizAttributes;
import pe.axiz.core.observability.AxizObservations;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/**
 * AWS SDK v2 interceptor that wraps every call (DynamoDB, SQS, SNS, S3, KMS, Secrets Manager...) in
 * a Micrometer observation: a CLIENT span with service and operation and the {@code axiz.aws.sdk}
 * timer. Works in native images (no agent, no bytecode manipulation). Register it on each client
 * builder through {@code overrideConfiguration}.
 */
public final class ObservationExecutionInterceptor implements ExecutionInterceptor {

  /** The observation of one call and the scope it opened, kept together. */
  private record Active(Observation observation, Observation.Scope scope) {}

  private static final ExecutionAttribute<Active> ACTIVE =
      new ExecutionAttribute<>("axiz.observation");

  private final ObservationRegistry registry;

  public ObservationExecutionInterceptor(ObservationRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
    String service = String.valueOf(attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME));
    String operation =
        String.valueOf(attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME));
    Observation observation =
        Observation.createNotStarted(AxizObservations.AWS_SDK, registry)
            .contextualName(service + "." + operation)
            .lowCardinalityKeyValue(AxizAttributes.AWS_SERVICE, service)
            .lowCardinalityKeyValue(AxizAttributes.AWS_OPERATION, operation)
            .start();
    attributes.putAttribute(ACTIVE, new Active(observation, observation.openScope()));
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
    active.scope().close();
    active.observation().stop();
  }
}
