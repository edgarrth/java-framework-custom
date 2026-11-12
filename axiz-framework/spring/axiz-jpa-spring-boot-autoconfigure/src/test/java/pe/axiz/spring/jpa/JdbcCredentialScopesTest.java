package pe.axiz.spring.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.adapter.aws.identity.AwsCredentialSource;
import pe.axiz.adapter.aws.identity.AwsCredentialSources;
import pe.axiz.spring.foundation.aws.AxizAwsIdentityAutoConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.jdbc.HostSpecBuilder;
import software.amazon.jdbc.hostavailability.SimpleHostAvailabilityStrategy;
import software.amazon.jdbc.profile.DriverConfigurationProfiles;
import software.amazon.jdbc.util.FullServicesContainer;

class JdbcCredentialScopesTest {
  private static final java.util.List<
          org.springframework.context.annotation.AnnotationConfigApplicationContext>
      CONTEXTS = new java.util.ArrayList<>();

  @org.junit.jupiter.api.AfterEach
  void closeTestContexts() {
    CONTEXTS.forEach(
        org.springframework.context.annotation.AnnotationConfigApplicationContext::close);
    CONTEXTS.clear();
  }

  private static pe.axiz.spring.foundation.aws.AwsIdentity identity(
      DefaultListableBeanFactory beans) {
    var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext(beans);
    context.register(AxizAwsIdentityAutoConfiguration.class);
    context.refresh();
    CONTEXTS.add(context);
    return context.getBean(pe.axiz.spring.foundation.aws.AwsIdentity.class);
  }

  @Test
  void externalWrapperPoolsRemainUntouchedEvenWhenStartedAndReservedScopesFailClosed() {
    var owner = processor("FIRST");
    var external = mock(HikariDataSource.class);
    when(external.getDriverClassName()).thenReturn("software.amazon.jdbc.Driver");
    when(external.isRunning()).thenReturn(true);
    var properties = new Properties();
    properties.setProperty("wrapperProfileName", "external-reporting");
    when(external.getDataSourceProperties()).thenReturn(properties);
    assertSame(external, owner.postProcessBeforeInitialization(external, "reporting"));
    assertFalse(owner.requiresDestruction(external));
    assertEquals("external-reporting", properties.getProperty("wrapperProfileName"));
    var managed = pool();
    try {
      owner.postProcessBeforeInitialization(managed, "managed");
      assertTrue(owner.requiresDestruction(managed));
      properties.setProperty("wrapperProfileName", JdbcIamCredentialScopes.PREFIX + "unknown");
      assertThrows(
          IllegalStateException.class,
          () -> owner.postProcessBeforeInitialization(external, "foreign-scope"));
      properties.setProperty("wrapperProfileName", "axiz-jpa-iam");
      assertThrows(
          IllegalStateException.class,
          () -> owner.postProcessBeforeInitialization(external, "already-started"));
    } finally {
      owner.destroy();
    }
    assertTrue(managed.isClosed());
    org.mockito.Mockito.verify(external, org.mockito.Mockito.never()).close();
  }

  @Test
  void ownedPoolSourceSurvivesDatabaseAndStsErrorsAndSuppliesRefreshedCredentials()
      throws Exception {
    var closed = new AtomicInteger();
    var denied = new AtomicBoolean();
    var credentials =
        new AtomicReference<AwsCredentials>(AwsBasicCredentials.create("FIRST", "fixture"));
    class Source implements AwsCredentialsProvider, SdkAutoCloseable {
      public AwsCredentials resolveCredentials() {
        if (denied.get()) throw new IllegalStateException("STS temporarily unavailable");
        return credentials.get();
      }

      public void close() {
        closed.incrementAndGet();
      }
    }
    var sourceOwner = new Source();
    String id =
        JdbcIamCredentialScopes.open(AwsCredentialSource.owned(sourceOwner, sourceOwner::close));
    var properties = new Properties();
    properties.setProperty("wrapperProfileName", id);
    properties.setProperty("user", "app");
    properties.setProperty("iamRegion", "us-east-1");
    var host =
        new HostSpecBuilder(new SimpleHostAvailabilityStrategy())
            .host("db.example")
            .port(5432)
            .build();
    var plugin =
        (AxizJdbcIamPluginFactory.Plugin)
            new AxizJdbcIamPluginFactory()
                .getInstance(mock(FullServicesContainer.class), properties);
    try {
      var network = new SQLException("connection refused", "08001");
      assertSame(
          network,
          assertThrows(
              SQLException.class,
              () ->
                  plugin.connect(
                      "jdbc:postgresql:",
                      host,
                      properties,
                      true,
                      () -> {
                        throw network;
                      })));
      assertEquals(0, closed.get());
      denied.set(true);
      assertEquals(
          "28000",
          assertThrows(
                  SQLException.class,
                  () ->
                      plugin.forceConnect(
                          "jdbc:postgresql:",
                          host,
                          properties,
                          false,
                          () -> mock(Connection.class)))
              .getSQLState());
      assertEquals(0, closed.get());
      denied.set(false);
      credentials.set(AwsSessionCredentials.create("REFRESHED", "fixture", "new-session"));
      plugin.forceConnect(
          "jdbc:postgresql:", host, properties, false, () -> mock(Connection.class));
      assertTrue(properties.getProperty("password").contains("REFRESHED"));
      assertTrue(properties.getProperty("password").contains("new-session"));
      assertEquals(0, closed.get());
    } finally {
      JdbcIamCredentialScopes.close(id);
      JdbcIamCredentialScopes.close(id);
    }
    assertEquals(1, closed.get());
    assertNull(JdbcIamCredentialScopes.find(id));
  }

  @Test
  void defaultSourceBelongsToContextAndClosesOnceAfterRegistrationFailureOrShutdown() {
    for (boolean fail : new boolean[] {false, true}) {
      var source =
          mock(
              AwsCredentialsProvider.class,
              org.mockito.Mockito.withSettings().extraInterfaces(SdkAutoCloseable.class));
      try (var defaults = mockStatic(AwsCredentialSources.class)) {
        defaults
            .when(AwsCredentialSources::create)
            .thenAnswer(
                call ->
                    AwsCredentialSource.owned(source, () -> ((SdkAutoCloseable) source).close()));
        var beans = new DefaultListableBeanFactory();
        var owner = new JdbcIamCredentials(identity(beans));
        var pool = mock(HikariDataSource.class);
        when(pool.getDriverClassName()).thenReturn("software.amazon.jdbc.Driver");
        var properties = new Properties();
        properties.setProperty("wrapperProfileName", JdbcIamProfiles.register(false));
        when(pool.getDataSourceProperties()).thenReturn(properties);
        var id = new AtomicReference<String>();
        doAnswer(
                call -> {
                  id.set(call.getArgument(1));
                  if (fail) throw new IllegalStateException("registration failed");
                  properties.setProperty("wrapperProfileName", id.get());
                  return null;
                })
            .when(pool)
            .addDataSourceProperty(
                org.mockito.ArgumentMatchers.eq("wrapperProfileName"),
                org.mockito.ArgumentMatchers.any());
        try {
          if (fail)
            assertThrows(
                IllegalStateException.class,
                () -> owner.postProcessBeforeInitialization(pool, "pool"));
          else {
            owner.postProcessBeforeInitialization(pool, "pool");
            owner.postProcessBeforeInitialization(pool, "pool");
            org.mockito.Mockito.verify((SdkAutoCloseable) source, org.mockito.Mockito.never())
                .close();
            owner.postProcessBeforeDestruction(pool, "pool");
          }
        } finally {
          owner.destroy();
          org.mockito.Mockito.verify((SdkAutoCloseable) source, org.mockito.Mockito.never())
              .close();
          beans.destroySingletons();
        }
        defaults.verify(AwsCredentialSources::create, org.mockito.Mockito.times(1));
        org.mockito.Mockito.verify((SdkAutoCloseable) source, org.mockito.Mockito.times(1)).close();
        assertNotNull(id.get());
        assertNull(JdbcIamCredentialScopes.find(id.get()));
        assertFalse(DriverConfigurationProfiles.contains(id.get()));
      }
    }
  }

  @Test
  void failedContextStartupClosesTheUnregisteredPoolAndRemovesItsScope() {
    class FailingPool extends HikariDataSource implements InitializingBean {
      @Override
      public void afterPropertiesSet() {
        throw new IllegalStateException("fixture initialization failure");
      }
    }
    var pool = new FailingPool();
    pool.setDriverClassName("software.amazon.jdbc.Driver");
    pool.addDataSourceProperty("wrapperProfileName", JdbcIamProfiles.register(false));
    var source =
        mock(
            AwsCredentialsProvider.class,
            org.mockito.Mockito.withSettings().extraInterfaces(SdkAutoCloseable.class));
    var closedAfterPool = new AtomicBoolean();
    try (var defaults = mockStatic(AwsCredentialSources.class)) {
      defaults
          .when(AwsCredentialSources::create)
          .thenAnswer(
              call ->
                  AwsCredentialSource.owned(
                      source,
                      () -> {
                        closedAfterPool.set(pool.isClosed());
                        ((SdkAutoCloseable) source).close();
                      }));
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
          .withUserConfiguration(AxizJpaAutoConfiguration.IamConfiguration.class)
          .withPropertyValues("axiz.jpa.aws-wrapper.enabled=true", "axiz.jpa.aws-wrapper.iam=true")
          .withBean("failedPool", FailingPool.class, () -> pool)
          .run(ctx -> assertNotNull(ctx.getStartupFailure()));
      org.mockito.Mockito.verify((SdkAutoCloseable) source, org.mockito.Mockito.times(1)).close();
    }
    String id = pool.getDataSourceProperties().getProperty("wrapperProfileName");
    assertTrue(id.startsWith(JdbcIamCredentialScopes.PREFIX));
    assertNull(JdbcIamCredentialScopes.find(id));
    assertFalse(DriverConfigurationProfiles.contains(id));
    assertTrue(pool.isClosed());
    assertTrue(closedAfterPool.get(), "Failed pool cleanup precedes shared identity closure");
  }

  private static JdbcIamCredentials processor(String access) {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "identity", (AwsCredentialsProvider) () -> AwsBasicCredentials.create(access, "fixture"));
    return new JdbcIamCredentials(identity(beans));
  }

  private static HikariDataSource pool() {
    var pool = new HikariDataSource();
    pool.setDriverClassName("software.amazon.jdbc.Driver");
    pool.addDataSourceProperty("wrapperProfileName", JdbcIamProfiles.register(false));
    return pool;
  }

  @Test
  void poolsHaveIndependentIdentitiesAndCleanupDoesNotAffectAnotherContext() {
    var first = processor("FIRST");
    var second = processor("SECOND");
    var poolA = pool();
    var poolB = pool();
    try {
      assertSame(poolA, first.postProcessBeforeInitialization(poolA, "first"));
      second.postProcessBeforeInitialization(poolB, "second");
      String a = poolA.getDataSourceProperties().getProperty("wrapperProfileName");
      String b = poolB.getDataSourceProperties().getProperty("wrapperProfileName");
      assertNotEquals(a, b);
      assertEquals("FIRST", JdbcIamCredentialScopes.find(a).resolveCredentials().accessKeyId());
      assertEquals("SECOND", JdbcIamCredentialScopes.find(b).resolveCredentials().accessKeyId());
      assertTrue(DriverConfigurationProfiles.getProfileConfiguration(a).getProperties().isEmpty());
      assertNull(
          DriverConfigurationProfiles.getProfileConfiguration(a)
              .getAwsCredentialsProviderHandler());
      first.postProcessBeforeInitialization(poolA, "first");
      assertEquals(a, poolA.getDataSourceProperties().getProperty("wrapperProfileName"));
      assertTrue(first.requiresDestruction(poolA));
      assertFalse(first.requiresDestruction("other"));
      first.postProcessBeforeDestruction("other", "other");
      first.postProcessBeforeDestruction(poolA, "first");
      assertNull(JdbcIamCredentialScopes.find(a));
      assertFalse(DriverConfigurationProfiles.contains(a));
      assertFalse(first.requiresDestruction(poolA));
      assertNotNull(JdbcIamCredentialScopes.find(b));
      first.postProcessBeforeDestruction(poolA, "first");
    } finally {
      first.destroy();
      second.destroy();
    }
    assertTrue(poolA.isClosed());
    assertTrue(poolB.isClosed());
  }

  @Test
  void externalProfilesAreIgnoredAndRegistrationFailureLeavesNoCredentialScope() {
    var owner = processor("FIRST");
    var pool = mock(HikariDataSource.class);
    when(pool.getDriverClassName()).thenReturn("software.amazon.jdbc.Driver");
    var properties = new Properties();
    when(pool.getDataSourceProperties()).thenReturn(properties);
    assertSame(pool, owner.postProcessBeforeInitialization(pool, "external"));
    assertFalse(owner.requiresDestruction(pool));
    properties.setProperty("wrapperProfileName", "axiz-jpa-iam");
    JdbcIamProfiles.register(false);
    var id = new AtomicReference<String>();
    doAnswer(
            call -> {
              id.set(call.getArgument(1));
              throw new IllegalStateException("fixture failure");
            })
        .when(pool)
        .addDataSourceProperty(
            org.mockito.ArgumentMatchers.eq("wrapperProfileName"),
            org.mockito.ArgumentMatchers.any());
    assertThrows(
        IllegalStateException.class, () -> owner.postProcessBeforeInitialization(pool, "failed"));
    assertNotNull(id.get());
    assertNull(JdbcIamCredentialScopes.find(id.get()));
    assertFalse(DriverConfigurationProfiles.contains(id.get()));
    assertFalse(owner.requiresDestruction(pool));
    owner.destroy();
  }

  @Test
  void pluginResolvesTheConnectionScopeAndFailsClosedAfterItIsRemoved() {
    String id =
        JdbcIamCredentialScopes.open(
            AwsCredentialSource.borrowed(() -> AwsBasicCredentials.create("FIRST", "fixture")));
    try {
      var services = mock(FullServicesContainer.class);
      var definition =
          software.amazon.jdbc.profile.ConfigurationProfileBuilder.get()
              .withName(id)
              .withProperties(new Properties())
              .build();
      when(services.getConfigurationProfile()).thenReturn(definition);
      var factory = new AxizJdbcIamPluginFactory();
      assertNotNull(factory.getInstance(services, new Properties()));
      JdbcIamCredentialScopes.close(id);
      assertThrows(
          IllegalStateException.class, () -> factory.getInstance(services, new Properties()));
    } finally {
      JdbcIamCredentialScopes.close(id);
    }
  }
}
