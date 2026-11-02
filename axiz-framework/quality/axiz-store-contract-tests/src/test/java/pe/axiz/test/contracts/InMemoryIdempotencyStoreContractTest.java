package pe.axiz.test.contracts;

import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;

class InMemoryIdempotencyStoreContractTest {

  @TestFactory
  Stream<DynamicTest> contract() {
    return IdempotencyStoreContract.checks(InMemoryIdempotencyStore::new).stream()
        .map(c -> DynamicTest.dynamicTest(c.name(), c::run));
  }
}
