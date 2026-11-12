package pe.axiz.adapter.aws;

import pe.axiz.adapter.aws.identity.AwsCredentialSource;

public final class RootFixture {
  private RootFixture() {}

  public static Class<?> identity() {
    return AwsCredentialSource.class;
  }
}
