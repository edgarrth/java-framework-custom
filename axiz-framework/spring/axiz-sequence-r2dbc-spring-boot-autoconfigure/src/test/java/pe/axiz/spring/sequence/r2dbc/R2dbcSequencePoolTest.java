package pe.axiz.spring.sequence.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

class R2dbcSequencePoolTest {
  @Test
  void reactiveReservationsUseAnIndependentBoundedPoolAndCloseOnlyTheirOwnPool() {
    ConnectionFactory driver = mock(ConnectionFactory.class);
    when(driver.create()).thenAnswer(ignored -> Mono.<Connection>never());
    when(driver.getMetadata()).thenReturn(() -> "test");
    var main =
        new ConnectionPool(
            ConnectionPoolConfiguration.builder(driver).initialSize(0).maxSize(1).build());
    try (var resources = R2dbcSequenceConnections.create(main, null, new MockEnvironment())) {
      assertThat(resources.connections()).isInstanceOf(ConnectionPool.class).isNotSameAs(main);
      assertThat(((ConnectionPool) resources.connections()).unwrap()).isSameAs(driver);
    }
    assertThat(main.isDisposed()).isFalse();
    main.dispose();
  }

  @Test
  void borrowedResourcesRemainOwnedByTheCallerAndPolicyIsValidated() throws Exception {
    ConnectionFactory factory = mock(ConnectionFactory.class);
    try (var resources = R2dbcSequenceConnections.create(factory, factory, new MockEnvironment())) {
      assertThat(resources.connections()).isSameAs(factory);
    }
    var invalid = new MockEnvironment().withProperty("axiz.sequence.pool.max-size", "0");
    assertThrows(
        IllegalArgumentException.class,
        () -> R2dbcSequenceConnections.create(factory, null, invalid));
    var invalidTimeout =
        new MockEnvironment().withProperty("axiz.sequence.pool.acquire-timeout", "1ms");
    assertThrows(
        IllegalArgumentException.class,
        () -> R2dbcSequenceConnections.create(factory, null, invalidTimeout));
  }
}
