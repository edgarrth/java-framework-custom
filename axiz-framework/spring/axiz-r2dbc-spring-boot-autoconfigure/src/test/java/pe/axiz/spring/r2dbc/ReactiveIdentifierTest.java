package pe.axiz.spring.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pe.axiz.core.id.Ulid;
import pe.axiz.core.id.UuidV7;
import pe.axiz.spring.test.BlockHoundExtension;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@ExtendWith(BlockHoundExtension.class)
class ReactiveIdentifierTest {
  @Test
  void concurrentEventLoopsDoNotParkWhileReservingMonotonicIdentifiers() {
    var clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    var uuids = UuidV7.generator(clock);
    var ulids = Ulid.generator(clock);
    Set<String> ids = ConcurrentHashMap.newKeySet();
    Flux.range(0, 8)
        .flatMap(
            worker ->
                Flux.range(0, 5000)
                    .publishOn(Schedulers.parallel())
                    .doOnNext(
                        i -> {
                          assertThat(ids.add(uuids.newId())).isTrue();
                          assertThat(ids.add(ulids.newId())).isTrue();
                        }),
            8)
        .then()
        .block(Duration.ofSeconds(30));
    assertThat(ids).hasSize(80_000);
    assertThat(BlockHoundExtension.detectedBlockingCalls()).isEmpty();
  }
}
