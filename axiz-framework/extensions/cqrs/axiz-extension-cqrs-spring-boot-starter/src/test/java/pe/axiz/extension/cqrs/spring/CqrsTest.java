package pe.axiz.extension.cqrs.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.errors.AccessException;
import pe.axiz.core.errors.CoreErrors;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.InMemoryIdempotencyStore;
import pe.axiz.extension.cqrs.AccessPolicy;
import pe.axiz.extension.cqrs.Caller;
import pe.axiz.extension.cqrs.Command;
import pe.axiz.extension.cqrs.CommandBus;
import pe.axiz.extension.cqrs.CommandHandler;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.RequiresAuthority;

class CqrsTest {

  @RequiresAuthority("SCOPE_cards:write")
  public record IssueCardCommand(String holder, String idempotencyKey)
      implements IdempotentCommand<String> {}

  public static class IssueCardHandler implements CommandHandler<IssueCardCommand, String> {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String handle(IssueCardCommand command) {
      return "card-" + calls.incrementAndGet() + "-" + command.holder();
    }
  }

  private static RequestMetadata caller(String subject) {
    return RequestMetadata.builder("corr", "req")
        .issuer("https://issuer.example")
        .clientId("app")
        .subject(subject)
        .build();
  }

  @Test
  void theCommandScopeIncludesTheCaller() {
    assertNotEquals(
        IdempotencyBehavior.scope("IssueCard", caller("alice")),
        IdempotencyBehavior.scope("IssueCard", caller("bob")));
    assertNotEquals(
        IdempotencyBehavior.scope("IssueCard", caller("alice")),
        IdempotencyBehavior.scope("IssueCard", null));
    assertEquals(
        IdempotencyBehavior.scope("IssueCard", caller("alice")),
        IdempotencyBehavior.scope("IssueCard", caller("alice")));
  }

  @Test
  void handlersAreDiscoveredAndPipelineApplies() {
    IssueCardHandler handler = new IssueCardHandler();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCqrsAutoConfiguration.class))
        .withBean(IssueCardHandler.class, () -> handler)
        .withBean(
            IdempotencyService.class,
            () ->
                new IdempotencyService(
                    new InMemoryIdempotencyStore(),
                    Duration.ofSeconds(30),
                    Duration.ofHours(1),
                    Clock.systemUTC()))
        .run(
            ctx -> {
              CommandBus bus = ctx.getBean(CommandBus.class);
              SecurityContextHolder.clearContext();
              assertThrows(
                  AccessException.class,
                  () -> bus.send(new IssueCardCommand("ana", "idempotency-key-00000001")));
              SecurityContextHolder.getContext()
                  .setAuthentication(new TestingAuthenticationToken("u", "p", "SCOPE_cards:write"));
              try {
                String first = bus.send(new IssueCardCommand("ana", "idempotency-key-00000001"));
                String again = bus.send(new IssueCardCommand("ana", "idempotency-key-00000001"));
                assertEquals("card-1-ana", first);
                assertEquals(first, again);
                assertEquals(1, handler.calls.get());
                // regression: another caller reusing the key runs its own command
                String other;
                try (RequestContext.Scope ignored = RequestContext.open(caller("bob"))) {
                  other = bus.send(new IssueCardCommand("ana", "idempotency-key-00000001"));
                }
                assertEquals("card-2-ana", other);
                assertEquals(2, handler.calls.get());
              } finally {
                SecurityContextHolder.clearContext();
              }
            });
  }

  public record RenameCardCommand(String holder) implements Command<String> {}

  public static class RenameCardHandler implements CommandHandler<RenameCardCommand, String> {
    @Override
    public String handle(RenameCardCommand command) {
      return command.holder();
    }
  }

  /** Regression: commands nothing protects stop startup in fail mode. */
  @Test
  void unprotectedCommandsFailStartupWhenRequired() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCqrsAutoConfiguration.class))
        .withBean(RenameCardHandler.class, RenameCardHandler::new)
        .withPropertyValues("axiz.cqrs.authorization.unprotected=fail")
        .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    AccessPolicy<RenameCardCommand> ownerOnly =
        new AccessPolicy<>() {
          @Override
          public Class<RenameCardCommand> requestType() {
            return RenameCardCommand.class;
          }

          @Override
          public void check(RenameCardCommand request, Caller caller) {
            if (!caller.isUser(request.holder())) {
              throw new AccessException(CoreErrors.FORBIDDEN, "not the holder");
            }
          }
        };
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AxizCqrsAutoConfiguration.class))
        .withBean(RenameCardHandler.class, RenameCardHandler::new)
        .withBean("ownerOnly", AccessPolicy.class, () -> ownerOnly)
        .withPropertyValues("axiz.cqrs.authorization.unprotected=fail")
        .run(
            ctx -> {
              CommandBus bus = ctx.getBean(CommandBus.class);
              SecurityContextHolder.getContext()
                  .setAuthentication(new TestingAuthenticationToken("ana", "p", "SCOPE_x"));
              try (RequestContext.Scope ignored = RequestContext.open(caller("ana"))) {
                assertEquals("ana", bus.send(new RenameCardCommand("ana")));
                assertThrows(AccessException.class, () -> bus.send(new RenameCardCommand("luis")));
              } finally {
                SecurityContextHolder.clearContext();
              }
            });
  }
}
