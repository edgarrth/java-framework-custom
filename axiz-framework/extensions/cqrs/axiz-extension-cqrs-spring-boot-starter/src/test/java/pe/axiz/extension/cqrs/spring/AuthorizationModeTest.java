package pe.axiz.extension.cqrs.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.axiz.extension.cqrs.AccessPolicies;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.ReactiveCommandHandler;
import pe.axiz.extension.cqrs.RequiresAuthority;
import reactor.core.publisher.Mono;

class AuthorizationModeTest {
  record UnprotectedCommand(String value) implements Command<String> {}

  @RequiresAuthority("SCOPE_payments:write")
  record ProtectedCommand(String value) implements Command<String> {}

  static final class ProtectedHandler implements CommandHandler<ProtectedCommand, String> {
    @Override
    public String handle(ProtectedCommand command) {
      return command.value();
    }
  }

  static final class OpenHandler implements CommandHandler<UnprotectedCommand, String> {
    @Override
    public String handle(UnprotectedCommand command) {
      return command.value();
    }
  }

  static final class NativeHandler implements ReactiveCommandHandler<ProtectedCommand, String> {
    @Override
    public Mono<String> handle(ProtectedCommand command) {
      return Mono.just(command.value());
    }
  }

  @Test
  void autoConfigurationFailsByDefaultWithoutAnExplicitPolicy() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCqrsAutoConfiguration.class))
        .withBean(OpenHandler.class, OpenHandler::new)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseMessage(
                      "Commands/queries without RequiresAuthority, AccessPolicy or OpenAccess: "
                          + UnprotectedCommand.class.getName()
                          + " (axiz.cqrs.authorization.unprotected=fail)");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"fail", "warn", "allow"})
  void missingSecurityCannotSilentlyDisableProtectedCommandsInEitherStyle(String mode) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCqrsAutoConfiguration.class))
        .withClassLoader(new FilteredClassLoader("org.springframework.security"))
        .withPropertyValues("axiz.cqrs.authorization.unprotected=" + mode)
        .withBean(ProtectedHandler.class, ProtectedHandler::new)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "CQRS handlers require an AuthorizationBehavior and Spring Security"));
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizReactiveCqrsAutoConfiguration.class))
        .withClassLoader(new FilteredClassLoader("org.springframework.security"))
        .withPropertyValues("axiz.cqrs.authorization.unprotected=" + mode)
        .withBean(NativeHandler.class, NativeHandler::new)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "Reactive CQRS handlers require native authorization and Spring Security"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"faill", "strict", "true", "", " fail "})
  void invalidModesFailEvenBeforeHandlersAreDiscovered(String mode) {
    assertThrows(
        IllegalArgumentException.class,
        () -> AuthorizationCoverage.verify(List.of(), AccessPolicies.none(), mode));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizationCoverage.verify(
                List.of(UnprotectedCommand.class), AccessPolicies.none(), mode));
  }

  @ParameterizedTest
  @ValueSource(strings = {"fail", "FAIL", "Fail"})
  void strictModeAlwaysRejectsUnprotectedCommands(String mode) {
    assertThrows(
        IllegalStateException.class,
        () ->
            AuthorizationCoverage.verify(
                List.of(UnprotectedCommand.class), AccessPolicies.none(), mode));
    assertDoesNotThrow(() -> AuthorizationCoverage.verify(List.of(), AccessPolicies.none(), mode));
  }

  @ParameterizedTest
  @ValueSource(strings = {"warn", "WARN", "allow", "ALLOW"})
  void intentionalModesRemainExplicit(String mode) {
    assertDoesNotThrow(
        () ->
            AuthorizationCoverage.verify(
                List.of(UnprotectedCommand.class), AccessPolicies.none(), mode));
  }
}
