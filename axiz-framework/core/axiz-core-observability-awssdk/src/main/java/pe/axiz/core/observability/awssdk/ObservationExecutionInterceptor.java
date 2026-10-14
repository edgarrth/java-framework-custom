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

  private static final ExecutionAttribute<Observation> OBSERVATION =
      new ExecutionAttribute<>("axiz.observation");
  private static final ExecutionAttribute<Observation.Scope> SCOPE =
      new ExecutionAttribute<>("axiz.observation.scope");

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
    attributes.putAttribute(OBSERVATION, observation);
    attributes.putAttribute(SCOPE, observation.openScope());
  }

  @Override
  public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
    Observation observation = attributes.getAttribute(OBSERVATION);
    if (observation != null) {
      context
          .httpResponse()
          .firstMatchingHeader("x-amzn-RequestId")
          .or(() -> context.httpResponse().firstMatchingHeader("x-amz-request-id"))
          .ifPresent(id -> observation.highCardinalityKeyValue(AxizAttributes.AWS_REQUEST_ID, id));
      observation.lowCardinalityKeyValue("outcome", "success");
      close(attributes, observation);
    }
  }

  @Override
  public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
    Observation observation = attributes.getAttribute(OBSERVATION);
    if (observation != null) {
      observation.lowCardinalityKeyValue("outcome", "error");
      observation.error(context.exception());
      close(attributes, observation);
    }
  }

  private static void close(ExecutionAttributes attributes, Observation observation) {
    Observation.Scope scope = attributes.getAttribute(SCOPE);
    if (scope != null) {
      scope.close();
    }
    observation.stop();
  }
}
