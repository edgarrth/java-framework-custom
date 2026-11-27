package pe.axiz.spring.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * El descubrimiento en frío y la descarga del JWKS se ejecutan en un hilo no bloqueante, no en un
 * puente bounded-elastic.
 */
@ExtendWith(BlockHoundExtension.class)
class NativeJwksTest {
  @Test
  void coldNativeVerificationAndConcurrentCachedReadsNeverBlock() throws Exception {
    try (var fixture = new JwksAvailabilityTest.Fixture();
        var decoders =
            JwksAvailabilityTest.decoders(
                fixture,
                true,
                new AxizSecurityProperties.Jwks(Duration.ofSeconds(2), Duration.ofSeconds(4)))) {
      String token = JwksAvailabilityTest.signed(fixture.issuer());
      var decoder = Objects.requireNonNull(decoders.reactive(fixture.issuer()));
      var cold =
          decoder.decode(token).subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
      assertThat(Objects.requireNonNull(cold).getSubject()).isEqualTo("customer");
      var results =
          Flux.range(0, 32)
              .flatMap(_ -> decoder.decode(token), 32)
              .subscribeOn(Schedulers.parallel())
              .collectList()
              .block(Duration.ofSeconds(5));
      assertThat(results).hasSize(32);
      assertThat(fixture.metadata).hasValue(1);
      assertThat(fixture.keys).hasValue(1);
      assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
    }
  }
}
