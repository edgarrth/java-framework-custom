package pe.axiz.adapter.observability.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.SenderContext;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import pe.axiz.core.observability.AxizAttributes;
import software.amazon.awssdk.core.ClientType;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

class ObservationExecutionInterceptorTest {
  @Test
  void nativeCompletionNeverLeaksOrClosesAnotherThreadsScope() throws Exception {
    List<Observation.Context> stopped = new ArrayList<>();
    var registry = recording(stopped);
    var interceptor = new ObservationExecutionInterceptor(registry);
    var attributes = call("SecretsManager", "GetSecretValue");
    attributes.putAttribute(SdkExecutionAttribute.CLIENT_TYPE, ClientType.ASYNC);
    var parent = Observation.start("request", registry);
    try (var parentScope = parent.openScope();
        var worker = Executors.newSingleThreadExecutor()) {
      interceptor.beforeExecution(() -> (SdkRequest) null, attributes);
      assertSame(
          parent, registry.getCurrentObservation(), "SDK submission must restore its caller");
      worker
          .submit(
              () -> {
                var independent = Observation.start("another-request", registry);
                try (var scope = independent.openScope()) {
                  interceptor.afterExecution(
                      answered(SdkHttpResponse.builder().statusCode(200).build()), attributes);
                  assertSame(
                      independent,
                      registry.getCurrentObservation(),
                      "completion must not alter a worker's current scope");
                } finally {
                  independent.stop();
                }
                assertNull(registry.getCurrentObservation());
              })
          .get(5, TimeUnit.SECONDS);
      assertSame(parent, registry.getCurrentObservation());
      assertSame(parent, stopped.getFirst().getParentObservation());
    } finally {
      parent.stop();
    }
    assertNull(registry.getCurrentObservation());
  }

  @Test
  void clientSpanPropagationIsInjectedBeforeSigningWithoutCrossingRequests() {
    var registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<SenderContext<Map<String, String>>>() {
              public boolean supportsContext(Observation.Context context) {
                return context instanceof SenderContext<?>;
              }

              public void onStart(SenderContext<Map<String, String>> context) {
                assertEquals(Kind.CLIENT, context.getKind());
                assertEquals("S3", context.getRemoteServiceName());
                context
                    .getSetter()
                    .set(
                        context.getCarrier(),
                        "traceparent",
                        context.getLowCardinalityKeyValue(AxizAttributes.AWS_OPERATION).getValue());
              }
            });
    var interceptor = new ObservationExecutionInterceptor(registry);
    var first = call("S3", "GetObject");
    var second = call("S3", "PutObject");
    first.putAttribute(SdkExecutionAttribute.CLIENT_TYPE, ClientType.ASYNC);
    second.putAttribute(SdkExecutionAttribute.CLIENT_TYPE, ClientType.ASYNC);
    var http =
        SdkHttpRequest.builder()
            .protocol("https")
            .host("s3.example.test")
            .method(SdkHttpMethod.GET)
            .build();
    var request =
        InterceptorContext.builder()
            .request(GetItemRequest.builder().tableName("test").build())
            .httpRequest(http)
            .build();
    assertSame(http, interceptor.modifyHttpRequest(request, first));
    interceptor.beforeExecution(() -> (SdkRequest) null, first);
    interceptor.beforeExecution(() -> (SdkRequest) null, second);
    assertNull(registry.getCurrentObservation());
    assertEquals(
        "GetObject",
        interceptor
            .modifyHttpRequest(request, first)
            .firstMatchingHeader("traceparent")
            .orElseThrow());
    assertEquals(
        "PutObject",
        interceptor
            .modifyHttpRequest(request, second)
            .firstMatchingHeader("traceparent")
            .orElseThrow());
    assertTrue(http.firstMatchingHeader("traceparent").isEmpty());
    interceptor.afterExecution(answered(SdkHttpResponse.builder().statusCode(200).build()), first);
    interceptor.afterExecution(answered(SdkHttpResponse.builder().statusCode(200).build()), second);
    assertNull(registry.getCurrentObservation());
  }

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
      public SdkResponse response() {
        return null;
      }

      @Override
      public SdkRequest request() {
        return null;
      }

      @Override
      public SdkHttpRequest httpRequest() {
        return null;
      }

      @Override
      public Optional<Publisher<ByteBuffer>> responsePublisher() {
        return Optional.empty();
      }

      @Override
      public Optional<InputStream> responseBody() {
        return Optional.empty();
      }

      @Override
      public Optional<RequestBody> requestBody() {
        return Optional.empty();
      }

      @Override
      public Optional<AsyncRequestBody> asyncRequestBody() {
        return Optional.empty();
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

    // S3 nombra la cabecera de otra forma
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
          public Optional<SdkHttpRequest> httpRequest() {
            return Optional.empty();
          }

          @Override
          public Optional<SdkHttpResponse> httpResponse() {
            return Optional.empty();
          }

          @Override
          public Optional<SdkResponse> response() {
            return Optional.empty();
          }
        },
        attributes);
    assertEquals(1, stopped.size());
    assertEquals("DynamoDb.PutItem", stopped.get(0).getContextualName());
    assertEquals("error", stopped.get(0).getLowCardinalityKeyValue("outcome").getValue());
    assertEquals(failure, stopped.get(0).getError());
  }
}
