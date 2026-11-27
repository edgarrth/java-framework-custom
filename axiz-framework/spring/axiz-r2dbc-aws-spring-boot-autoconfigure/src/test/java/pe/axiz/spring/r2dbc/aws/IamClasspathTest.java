package pe.axiz.spring.r2dbc.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactoryOptions;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.r2dbc.autoconfigure.ConnectionFactoryOptionsBuilderCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.kit.r2dbc.DatabasePasswordProvider;
import pe.axiz.spring.aws.AxizAwsIdentityAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcAutoConfiguration;
import pe.axiz.spring.r2dbc.AxizR2dbcPasswordAutoConfiguration;
import pe.axiz.spring.r2dbc.ReaderDatabase;
import reactor.core.publisher.Mono;

class IamClasspathTest {
  @TempDir Path temporary;

  @Test
  void optionalAuthenticationAlsoWorksWithTheAwsArtifactsPhysicallyAbsent() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    String isolated =
        Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
            .filter(entry -> !entry.contains("axiz-adapter-database-auth-aws"))
            .filter(entry -> !Path.of(entry).getFileName().toString().startsWith("rds-"))
            .collect(Collectors.joining(File.pathSeparator));
    Path log = temporary.resolve("without-aws-auth.log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                isolated,
                WithoutAws.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue(), Files.readString(log));
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  public static final class WithoutAws {
    private WithoutAws() {}

    public static void main(String[] args) {
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName("pe.axiz.adapter.databaseauth.aws.RdsIamPasswordProvider"));
      assertThrows(
          ClassNotFoundException.class,
          () -> Class.forName("software.amazon.awssdk.services.rds.RdsUtilities"));
      var test = new IamClasspathTest();
      test.ordinaryPasswordAndReaderWorkWithoutAwsAuthenticationClasses();
      test.explicitlyEnabledIamFailsClearlyWhenItsOptionalAdapterIsAbsent();
      test.aCustomNeutralPasswordProviderNeedsNoAwsAdapter();
    }
  }

  private ApplicationContextRunner withoutAwsAdapter() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizAwsIdentityAutoConfiguration.class))
        .withClassLoader(
            new FilteredClassLoader(
                "pe.axiz.adapter.databaseauth.aws", "software.amazon.awssdk.services.rds"))
        .withConfiguration(
            AutoConfigurations.of(
                AxizR2dbcAutoConfiguration.class,
                AxizR2dbcIamAutoConfiguration.class,
                AxizR2dbcPasswordAutoConfiguration.class));
  }

  @Test
  void ordinaryPasswordAndReaderWorkWithoutAwsAuthenticationClasses() {
    withoutAwsAdapter()
        .withPropertyValues(
            "spring.r2dbc.username=app",
            "spring.r2dbc.password=local-only",
            "axiz.config.environment=test",
            "axiz.transport.allow-insecure-data-stores=true",
            "axiz.r2dbc.reader.url=r2dbc:axiztest://localhost/reader")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(ReaderDatabase.class);
              assertThat(context).doesNotHaveBean(DatabasePasswordProvider.class);
            });
  }

  @Test
  void explicitlyEnabledIamFailsClearlyWhenItsOptionalAdapterIsAbsent() {
    withoutAwsAdapter()
        .withPropertyValues(
            "spring.r2dbc.url=r2dbc:postgresql://localhost/app",
            "spring.r2dbc.username=app",
            "axiz.r2dbc.iam.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("pe.axiz:axiz-adapter-database-auth-aws");
            });
  }

  @Test
  void explicitlyEnabledIamFailsClearlyWhenOnlyTheSdkIsMissing() {
    withoutAwsAdapter()
        .withClassLoader(new FilteredClassLoader("software.amazon.awssdk.services.rds"))
        .withPropertyValues(
            "spring.r2dbc.url=r2dbc:postgresql://localhost/app",
            "spring.r2dbc.username=app",
            "axiz.r2dbc.iam.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("pe.axiz:axiz-adapter-database-auth-aws");
            });
  }

  @Test
  void aCustomNeutralPasswordProviderNeedsNoAwsAdapter() {
    DatabasePasswordProvider passwords = () -> "custom-token";
    withoutAwsAdapter()
        .withPropertyValues("axiz.r2dbc.iam.enabled=true")
        .withBean(DatabasePasswordProvider.class, () -> passwords)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(DatabasePasswordProvider.class);
              var options =
                  ConnectionFactoryOptions.parse("r2dbc:postgresql://localhost/app").mutate();
              context.getBean(ConnectionFactoryOptionsBuilderCustomizer.class).customize(options);
              assertEquals(
                  "custom-token",
                  Mono.from(
                          (Publisher<?>)
                              options.build().getValue(ConnectionFactoryOptions.PASSWORD))
                      .block());
            });
  }
}
