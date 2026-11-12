package pe.axiz.adapter.messaging.kafka.fixture;

import pe.axiz.adapter.aws.identity.AwsCredentialSources;

public class UnapprovedAwsDependency {
  public Class<?> dependency() {
    return AwsCredentialSources.class;
  }
}
