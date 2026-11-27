package pe.axiz.spring.outbox.jdbc;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import pe.axiz.quality.containers.TestContainerImages;

class PostgresOutboxIT {
  @Test
  void postgresCommitsOnlySuccessfulBusinessTransactionsAndRelaysOnce() {
    try (var postgres = TestContainerImages.postgres()) {
      postgres.start();
      var source = new PGSimpleDataSource();
      source.setURL(postgres.getJdbcUrl());
      source.setUser(postgres.getUsername());
      source.setPassword(postgres.getPassword());
      OutboxTest.assertTransactionalRelay(source);
    }
  }
}
