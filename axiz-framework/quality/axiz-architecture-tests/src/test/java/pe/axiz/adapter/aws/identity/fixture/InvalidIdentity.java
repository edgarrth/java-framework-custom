package pe.axiz.adapter.aws.identity.fixture;

import pe.axiz.adapter.aws.transport.AsyncAwsClients;

public final class InvalidIdentity {
  private InvalidIdentity() {}

  public static Class<?> transport() {
    return AsyncAwsClients.class;
  }
}
