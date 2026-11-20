package pe.axiz.demo.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.security.auth.callback.Callback;
import javax.security.auth.login.AppConfigurationEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.adapter.kafka.msk.AxizIamClientCallbackHandler;
import pe.axiz.core.secrets.SecretResolver;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.jpa.aws.AxizJpaAwsAutoConfiguration;
import pe.axiz.spring.jpa.aws.AxizJpaAwsEnvironmentPostProcessor;
import pe.axiz.spring.messaging.kafka.KafkaClientAuthentication;
import pe.axiz.spring.messaging.msk.AxizMessagingMskAutoConfiguration;
import pe.axiz.spring.r2dbc.aws.AxizR2dbcIamAutoConfiguration;
import pe.axiz.spring.secrets.AxizSecretsAutoConfiguration;
import pe.axiz.spring.secrets.ReactiveSecrets;
import pe.axiz.spring.secrets.file.AxizSecretsFileAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.msk.auth.iam.internals.AWSCredentialsCallback;

/** Local credential contracts. These tests never claim a managed RDS/MSK authentication. */
class IdentityRecipesTest {
  private static AwsCredentialsProvider identity() {
    return () -> AwsSessionCredentials.create("ASIADEMO", "synthetic-secret", "synthetic-session");
  }

  @Test
  void r2dbcIamSignsAnExpiringPasswordWithTheSelectedApplicationIdentity(TestReporter evidence) {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class, AxizR2dbcIamAutoConfiguration.class))
        .withBean(AwsCredentialsProvider.class, IdentityRecipesTest::identity)
        .withPropertyValues(
            "axiz.r2dbc.iam.enabled=true",
            "axiz.aws.region=us-east-1",
            "spring.r2dbc.url=r2dbc:postgresql://demo.cluster.example:5432/payments",
            "spring.r2dbc.username=payments_user")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              String token = context.getBean(DatabasePasswordProvider.class).password().toString();
              assertThat(token)
                  .contains(
                      "DBUser=payments_user",
                      "Action=connect",
                      "X-Amz-Signature=",
                      "ASIADEMO",
                      "X-Amz-Security-Token=synthetic-session",
                      "X-Amz-Expires=900")
                  .doesNotContain("synthetic-secret");
            });

    evidence.publishEntry("axiz.capability.r2dbc-aws", "reactive");
  }

  @Test
  void jdbcIamOwnsAPoolScopedCredentialProfileWithoutEmbeddingSecrets(TestReporter evidence) {
    var environment =
        new MockEnvironment()
            .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
            .withProperty("axiz.jpa.aws-wrapper.iam", "true");
    new AxizJpaAwsEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
    assertThat(
            environment.getProperty(
                "spring.datasource.hikari.data-source-properties.wrapperProfileName"))
        .isEqualTo("axiz-jpa-iam");
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AxizAwsIdentityAutoConfiguration.class, AxizJpaAwsAutoConfiguration.class))
        .withUserConfiguration(JdbcPool.class)
        .withBean(AwsCredentialsProvider.class, IdentityRecipesTest::identity)
        .withPropertyValues("axiz.jpa.aws-wrapper.enabled=true", "axiz.jpa.aws-wrapper.iam=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var pool = context.getBean(HikariDataSource.class);
              assertThat(pool.getDataSourceProperties().getProperty("wrapperProfileName"))
                  .isNotEqualTo("axiz-jpa-iam")
                  .startsWith("axiz-");
              assertThat(pool.getPassword()).isNull();
              assertThat(pool.getDataSourceProperties().toString())
                  .doesNotContain("synthetic-secret");
            });

    evidence.publishEntry("axiz.capability.jpa-aws", "servlet");
  }

  @Configuration(proxyBeanMethods = false)
  static class JdbcPool {
    @Bean
    HikariDataSource pool() {
      var pool = new HikariDataSource();
      pool.setDriverClassName("software.amazon.jdbc.Driver");
      pool.setJdbcUrl("jdbc:aws-wrapper:postgresql://demo.cluster.example:5432/payments");
      pool.setUsername("payments_user");
      pool.addDataSourceProperty("wrapperProfileName", "axiz-jpa-iam");
      return pool;
    }
  }

  @Test
  void mskCallbackUsesSelectedTemporaryCredentialsAndRequiresTls(TestReporter evidence) {
    for (String stack : List.of("servlet", "reactive")) {
      RecipeStacks.runner(stack)
          .withConfiguration(
              AutoConfigurations.of(
                  AxizAwsIdentityAutoConfiguration.class, AxizMessagingMskAutoConfiguration.class))
          .withBean(AwsCredentialsProvider.class, IdentityRecipesTest::identity)
          .withPropertyValues("axiz.messaging.kafka.authentication=msk")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var settings = new HashMap<String, Object>();
                context.getBean(KafkaClientAuthentication.class).configure(settings);
                assertThat(settings)
                    .containsEntry("security.protocol", "SASL_SSL")
                    .containsEntry("sasl.mechanism", "AWS_MSK_IAM");
                var handler = new AxizIamClientCallbackHandler();
                try {
                  handler.configure(
                      settings,
                      "AWS_MSK_IAM",
                      List.of(
                          new AppConfigurationEntry(
                              "software.amazon.msk.auth.iam.IAMLoginModule",
                              AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                              Map.of())));
                  var callback = new AWSCredentialsCallback();
                  handler.handle(new Callback[] {callback});
                  assertThat(callback.getAwsCredentials().accessKeyId()).isEqualTo("ASIADEMO");
                  assertThat(settings.toString())
                      .doesNotContain("synthetic-secret", "synthetic-session");
                } finally {
                  handler.close();
                }
                assertThrows(
                    IllegalStateException.class,
                    () -> handler.handle(new Callback[] {new AWSCredentialsCallback()}));
              });

      evidence.publishEntry("axiz.capability.messaging-msk", stack);
    }
  }

  @Test
  void mountedSecretsAreReadByBothStacksAndNeverRemainUsableAfterClose(
      @TempDir Path directory, TestReporter evidence) throws Exception {
    String synthetic = UUID.randomUUID().toString();
    Files.writeString(directory.resolve("client-key"), synthetic);
    var configurations =
        AutoConfigurations.of(
            AxizSecretsFileAutoConfiguration.class, AxizSecretsAutoConfiguration.class);
    new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withPropertyValues("axiz.secrets.directory=" + directory, "axiz.secrets.environment=false")
        .run(
            context -> {
              var value = context.getBean(SecretResolver.class).require("client-key");
              try (value) {
                assertThat(value.reveal()).isEqualTo(synthetic);
              }
              assertThrows(IllegalStateException.class, value::reveal);
              evidence.publishEntry("axiz.capability.secrets-file", "servlet");
            });
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(configurations)
        .withPropertyValues("axiz.secrets.directory=" + directory, "axiz.secrets.environment=false")
        .run(
            context -> {
              var value = context.getBean(ReactiveSecrets.class).require("client-key").block();
              assertThat(value).isNotNull();
              try (value) {
                assertThat(value.reveal()).isEqualTo(synthetic);
              }
              assertThrows(IllegalStateException.class, value::reveal);
              evidence.publishEntry("axiz.capability.secrets-file", "reactive");
            });
  }
}
