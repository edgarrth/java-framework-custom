package pe.axiz.spring.jpa.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.jdbc.HostSpecBuilder;
import software.amazon.jdbc.hostavailability.SimpleHostAvailabilityStrategy;
import software.amazon.jdbc.profile.DriverConfigurationProfiles;
import software.amazon.jdbc.util.FullServicesContainer;

class JdbcIamPolicyTest {
  @Test
  void blankFrameworkRegionUsesNormalizedExplicitSdkProperty() {
    var env =
        new MockEnvironment()
            .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
            .withProperty("axiz.jpa.aws-wrapper.iam", "true")
            .withProperty("axiz.aws.region", "  ")
            .withProperty("aws.region", " eu-west-1 ");
    new AxizJpaAwsEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication());
    assertEquals(
        "eu-west-1", env.getProperty("spring.datasource.hikari.data-source-properties.iamRegion"));
  }

  @Test
  void unscopedPluginsFailBeforeResolvingCredentialsAndScopedPluginsOnlyBorrow() throws Exception {
    var factory = new AxizJdbcIamPluginFactory();
    var services = mock(FullServicesContainer.class);
    var properties = new Properties();
    assertThrows(IllegalStateException.class, () -> factory.getInstance(services, properties));
    properties.setProperty("wrapperProfileName", JdbcIamProfiles.register(false));
    assertThrows(IllegalStateException.class, () -> factory.getInstance(services, properties));
    var closes = new AtomicInteger();
    String id = JdbcIamCredentialScopes.open(AwsCredentialSource.borrowed(new Source(closes)));
    properties.setProperty("wrapperProfileName", id);
    properties.setProperty("user", "app");
    properties.setProperty("iamRegion", "us-east-1");
    try {
      var plugin = (AxizJdbcIamPluginFactory.Plugin) factory.getInstance(services, properties);
      plugin.connect(
          "jdbc:postgresql:",
          new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
              .host("db.example")
              .port(5432)
              .build(),
          properties,
          true,
          () -> mock(Connection.class));
      assertTrue(properties.getProperty("password").contains("FIXTUREKEY"));
      assertEquals(0, closes.get());
    } finally {
      JdbcIamCredentialScopes.close(id);
    }
    assertEquals(0, closes.get());
    assertThrows(IllegalStateException.class, () -> factory.getInstance(services, properties));
  }

  @Test
  void physicalReconnectsSignAgainAndErrorsNeverCloseTheScopeOwner() throws Exception {
    var closed = new AtomicInteger();
    var denied = new java.util.concurrent.atomic.AtomicBoolean();
    var source =
        new Source(closed) {
          @Override
          public AwsCredentials resolveCredentials() {
            if (denied.get()) throw new IllegalStateException("STS denied");
            return super.resolveCredentials();
          }
        };
    String id = JdbcIamCredentialScopes.open(AwsCredentialSource.owned(source, source::close));
    var properties = new Properties();
    properties.setProperty("wrapperProfileName", id);
    properties.setProperty("user", "app");
    properties.setProperty("iamRegion", "us-east-1");
    var host =
        new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
            .host("localhost")
            .port(5432)
            .build();
    var plugin =
        (AxizJdbcIamPluginFactory.Plugin)
            new AxizJdbcIamPluginFactory()
                .getInstance(mock(FullServicesContainer.class), properties);
    var connection = mock(Connection.class);
    try {
      assertTrue(plugin.getSubscribedMethods().containsAll(List.of("connect", "forceConnect")));
      assertSame(
          connection, plugin.connect("jdbc:postgresql:", host, properties, true, () -> connection));
      assertTrue(properties.getProperty("password").contains("FIXTUREKEY"));
      properties.setProperty("iamHost", "reader.example");
      assertSame(
          connection,
          plugin.forceConnect("jdbc:postgresql:", host, properties, false, () -> connection));
      assertTrue(properties.getProperty("password").startsWith("reader.example:5432/"));
      var network = new SQLException("socket failure", "08006");
      assertSame(
          network,
          assertThrows(
              SQLException.class,
              () ->
                  plugin.connect(
                      "jdbc:postgresql:",
                      host,
                      properties,
                      false,
                      () -> {
                        throw network;
                      })));
      assertEquals(0, closed.get());
      plugin.forceConnect("jdbc:postgresql:", host, properties, false, () -> connection);
      denied.set(true);
      assertEquals(
          "28000",
          assertThrows(
                  SQLException.class,
                  () ->
                      plugin.connect("jdbc:postgresql:", host, properties, true, () -> connection))
              .getSQLState());
      assertEquals(0, closed.get());
      properties.setProperty("user", " ");
      assertEquals(
          "28000",
          assertThrows(
                  SQLException.class,
                  () ->
                      plugin.connect("jdbc:postgresql:", host, properties, true, () -> connection))
              .getSQLState());
      properties.setProperty("awsProfile", "other-role");
      assertEquals(
          "HY000",
          assertThrows(
                  SQLException.class,
                  () ->
                      plugin.connect("jdbc:postgresql:", host, properties, true, () -> connection))
              .getSQLState());
    } finally {
      JdbcIamCredentialScopes.close(id);
      JdbcIamCredentialScopes.close(id);
    }
    assertEquals(1, closed.get());
  }

  @Test
  void profilesAreCredentialFreeAndCannotBeSilentlyReplacedByRawSdkOverrides() {
    var epp = new AxizJpaAwsEnvironmentPostProcessor();
    for (boolean split : new boolean[] {true, false}) {
      var env =
          new MockEnvironment()
              .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
              .withProperty("axiz.jpa.aws-wrapper.iam", "true")
              .withProperty("axiz.jpa.aws-wrapper.read-write-splitting", Boolean.toString(split))
              .withProperty("axiz.aws.region", " us-east-1 ");
      epp.postProcessEnvironment(env, new SpringApplication());
      epp.postProcessEnvironment(env, new SpringApplication());
      var profile =
          DriverConfigurationProfiles.getProfileConfiguration(
              env.getProperty(
                  "spring.datasource.hikari.data-source-properties.wrapperProfileName"));
      assertNotNull(profile);
      assertNull(profile.getAwsCredentialsProviderHandler());
      assertSame(AxizJdbcIamPluginFactory.class, profile.getPluginFactories().getLast());
      assertEquals(split ? 4 : 3, profile.getPluginFactories().size());
      assertEquals(
          "us-east-1",
          env.getProperty("spring.datasource.hikari.data-source-properties.iamRegion"));
      assertNull(env.getProperty("spring.datasource.hikari.data-source-properties.wrapperPlugins"));
    }
    for (String setting : List.of("wrapperPlugins", "wrapperProfileName")) {
      var env =
          new MockEnvironment()
              .withProperty("axiz.jpa.aws-wrapper.enabled", "true")
              .withProperty("axiz.jpa.aws-wrapper.iam", "true")
              .withProperty("spring.datasource.hikari.data-source-properties." + setting, "custom");
      assertThrows(
          IllegalStateException.class,
          () -> epp.postProcessEnvironment(env, new SpringApplication()));
    }
  }

  private static class Source implements AwsCredentialsProvider, SdkAutoCloseable {
    private final AtomicInteger closed;

    private Source(AtomicInteger closed) {
      this.closed = closed;
    }

    @Override
    public AwsCredentials resolveCredentials() {
      return AwsBasicCredentials.create("FIXTUREKEY", "fixture-secret");
    }

    @Override
    public void close() {
      closed.incrementAndGet();
    }
  }
}
