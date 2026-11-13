package pe.axiz.spring.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BoundedResponseCaptureTest {
  @Test
  void largeResponseWritesThroughAndRetainsAtMostLimitPlusOne() throws Exception {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    response.setContentType("application/json");
    var wrapper = new BoundedCaptureResponseWrapper(request, response, 32);
    byte[] body = ("{\"data\":\"" + "x".repeat(1_000_000) + "\"}").getBytes(StandardCharsets.UTF_8);
    wrapper.getOutputStream().write(body);
    wrapper.getOutputStream().flush();
    assertThat(response.getContentAsByteArray()).isEqualTo(body);
    assertThat(wrapper.capturedBytes()).hasSize(33);
    assertThat(wrapper.complete()).isFalse();
    assertThat(response.isCommitted()).isTrue();
  }

  @Test
  void flushReachesClientBeforeTheRestOfTheResponse() throws Exception {
    var response = new MockHttpServletResponse();
    response.setContentType("application/json");
    var wrapper = new BoundedCaptureResponseWrapper(new MockHttpServletRequest(), response, 128);
    wrapper.getOutputStream().write("{\"first\":1,".getBytes(StandardCharsets.UTF_8));
    wrapper.flushBuffer();
    assertThat(response.getContentAsString()).isEqualTo("{\"first\":1,");
    wrapper.getOutputStream().write("\"last\":2}".getBytes(StandardCharsets.UTF_8));
    assertThat(response.getContentAsString()).isEqualTo("{\"first\":1,\"last\":2}");
  }

  @Test
  void streamingBinaryAndAttachmentsAreNeverCaptured() throws Exception {
    for (String type :
        new String[] {"text/event-stream", "application/x-ndjson", "application/octet-stream"}) {
      var response = new MockHttpServletResponse();
      response.setContentType(type);
      var wrapper = new BoundedCaptureResponseWrapper(new MockHttpServletRequest(), response, 16);
      wrapper.getOutputStream().write("payload".getBytes(StandardCharsets.UTF_8));
      assertThat(wrapper.capturedBytes()).isEmpty();
      assertThat(wrapper.eligible()).isFalse();
      assertThat(response.getContentAsString()).isEqualTo("payload");
    }
  }

  @Test
  void writerResetAndErrorResponsesPreserveServletSemantics() throws Exception {
    var response = new MockHttpServletResponse();
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8);
    var wrapper = new BoundedCaptureResponseWrapper(new MockHttpServletRequest(), response, 64);
    var writer = wrapper.getWriter();
    writer.write("discarded");
    wrapper.resetBuffer();
    writer.write("{\"error\":true}");
    wrapper.finish();
    assertThat(response.getContentAsString()).isEqualTo("{\"error\":true}");
    assertThat(new String(wrapper.capturedBytes(), StandardCharsets.UTF_8))
        .isEqualTo("{\"error\":true}");
  }
}
