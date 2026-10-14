package pe.axiz.core.observability.awssdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

class ObservationExecutionInterceptorTest {

  @Test
  void failedCallIsObservedWithServiceAndOperation() {
    List<Observation.Context> stopped = new ArrayList<>();
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    ObservationExecutionInterceptor interceptor = new ObservationExecutionInterceptor(registry);
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, "DynamoDb");
    attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "PutItem");
    Context.BeforeExecution before = () -> (SdkRequest) null;
    interceptor.beforeExecution(before, attributes);
    RuntimeException failure = new RuntimeException("throttled");
    interceptor.onExecutionFailure(
        new Context.FailedExecution() {
          @Override
          public Throwable exception() {
            return failure;
          }

          @Override
          public SdkRequest request() {
            return null;
          }

          @Override
          public java.util.Optional<software.amazon.awssdk.http.SdkHttpRequest> httpRequest() {
            return java.util.Optional.empty();
          }

          @Override
          public java.util.Optional<software.amazon.awssdk.http.SdkHttpResponse> httpResponse() {
            return java.util.Optional.empty();
          }

          @Override
          public java.util.Optional<software.amazon.awssdk.core.SdkResponse> response() {
            return java.util.Optional.empty();
          }
        },
        attributes);
    assertEquals(1, stopped.size());
    assertEquals("DynamoDb.PutItem", stopped.get(0).getContextualName());
    assertEquals("error", stopped.get(0).getLowCardinalityKeyValue("outcome").getValue());
    assertEquals(failure, stopped.get(0).getError());
  }
}
