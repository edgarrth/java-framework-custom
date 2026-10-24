package pe.axiz.core.observability.awssdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pe.axiz.core.observability.AxizAttributes;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpResponse;

class ObservationExecutionInterceptorTest {

  private static ObservationRegistry recording(List<Observation.Context> stopped) {
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
    return registry;
  }

  private static ExecutionAttributes call(String service, String operation) {
    ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, service);
    attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, operation);
    return attributes;
  }

  private static Context.AfterExecution answered(SdkHttpResponse response) {
    return new Context.AfterExecution() {
      @Override
      public SdkHttpResponse httpResponse() {
        return response;
      }

      @Override
      public software.amazon.awssdk.core.SdkResponse response() {
        return null;
      }

      @Override
      public SdkRequest request() {
        return null;
      }

      @Override
      public software.amazon.awssdk.http.SdkHttpRequest httpRequest() {
        return null;
      }

      @Override
      public java.util.Optional<org.reactivestreams.Publisher<java.nio.ByteBuffer>>
          responsePublisher() {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<java.io.InputStream> responseBody() {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<software.amazon.awssdk.core.sync.RequestBody> requestBody() {
        return java.util.Optional.empty();
      }

      @Override
      public java.util.Optional<software.amazon.awssdk.core.async.AsyncRequestBody>
          asyncRequestBody() {
        return java.util.Optional.empty();
      }
    };
  }

  @Test
  void successfulCallKeepsTheRequestIdOfAws() {
    List<Observation.Context> stopped = new ArrayList<>();
    ObservationExecutionInterceptor interceptor =
        new ObservationExecutionInterceptor(recording(stopped));

    ExecutionAttributes dynamo = call("DynamoDb", "GetItem");
    interceptor.beforeExecution(() -> (SdkRequest) null, dynamo);
    interceptor.afterExecution(
        answered(
            SdkHttpResponse.builder().statusCode(200).putHeader("x-amzn-RequestId", "R-1").build()),
        dynamo);

    // S3 names the header differently
    ExecutionAttributes s3 = call("S3", "GetObject");
    interceptor.beforeExecution(() -> (SdkRequest) null, s3);
    interceptor.afterExecution(
        answered(
            SdkHttpResponse.builder().statusCode(200).putHeader("x-amz-request-id", "R-2").build()),
        s3);

    ExecutionAttributes none = call("Sqs", "SendMessage");
    interceptor.beforeExecution(() -> (SdkRequest) null, none);
    interceptor.afterExecution(answered(SdkHttpResponse.builder().statusCode(200).build()), none);

    assertEquals(3, stopped.size());
    assertEquals("success", stopped.get(0).getLowCardinalityKeyValue("outcome").getValue());
    assertEquals(
        "R-1", stopped.get(0).getHighCardinalityKeyValue(AxizAttributes.AWS_REQUEST_ID).getValue());
    assertEquals(
        "R-2", stopped.get(1).getHighCardinalityKeyValue(AxizAttributes.AWS_REQUEST_ID).getValue());
    assertNull(stopped.get(2).getHighCardinalityKeyValue(AxizAttributes.AWS_REQUEST_ID));
  }

  @Test
  void callsThatWereNotStartedHereAreIgnored() {
    List<Observation.Context> stopped = new ArrayList<>();
    ObservationExecutionInterceptor interceptor =
        new ObservationExecutionInterceptor(recording(stopped));
    ExecutionAttributes foreign = call("DynamoDb", "GetItem");
    interceptor.afterExecution(
        answered(SdkHttpResponse.builder().statusCode(200).build()), foreign);
    interceptor.onExecutionFailure(null, foreign);
    assertEquals(List.of(), stopped);
  }

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
