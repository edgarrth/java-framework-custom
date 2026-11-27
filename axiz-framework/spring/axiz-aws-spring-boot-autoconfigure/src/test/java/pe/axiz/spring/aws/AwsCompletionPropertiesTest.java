package pe.axiz.spring.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class AwsCompletionPropertiesTest {
  @Test
  void defaultsAndConfiguredBoundsBindWithValidation() {
    var defaults =
        Binder.get(new MockEnvironment())
            .bindOrCreate("axiz.aws.completion", AxizAwsCompletionProperties.class);
    assertEquals(2, defaults.workers());
    assertEquals(256, defaults.queueCapacity());
    var configured =
        Binder.get(
                new MockEnvironment()
                    .withProperty("axiz.aws.completion.workers", "4")
                    .withProperty("axiz.aws.completion.queue-capacity", "32"))
            .bindOrCreate("axiz.aws.completion", AxizAwsCompletionProperties.class);
    assertEquals(4, configured.workers());
    assertEquals(32, configured.queueCapacity());
    for (int[] invalid : new int[][] {{0, 1}, {65, 1}, {1, 0}, {1, 65537}})
      assertThrows(
          IllegalArgumentException.class,
          () -> new AxizAwsCompletionProperties(invalid[0], invalid[1]));
  }
}
