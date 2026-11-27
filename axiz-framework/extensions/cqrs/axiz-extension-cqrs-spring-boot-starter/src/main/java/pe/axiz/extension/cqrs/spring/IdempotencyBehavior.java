package pe.axiz.extension.cqrs.spring;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.context.RequestContext;
import pe.axiz.core.context.RequestMetadata;
import pe.axiz.core.errors.NoEffect;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.extension.cqrs.IdempotentCommand;
import pe.axiz.extension.cqrs.Invocation;
import pe.axiz.extension.cqrs.PipelineBehavior;
import tools.jackson.databind.json.JsonMapper;

/**
 * Comandos idempotentes: el primer resultado se almacena (JSON) y se devuelve de nuevo para la
 * misma clave, el mismo comando y el mismo llamador; un comando distinto con la misma clave es un
 * problema 422. Se ejecuta después del behavior de autorización, de modo que un replay solo se
 * devuelve a un llamador que sigue autorizado a ejecutar el comando.
 */
public final class IdempotencyBehavior implements PipelineBehavior {

  private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyBehavior.class);

  private final IdempotencyService service;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "JsonMapper is an injected collaborator whose identity and lifecycle must be shared, "
              + "not copied.")
  private final JsonMapper mapper;

  public IdempotencyBehavior(IdempotencyService service, JsonMapper mapper) {
    this.service = Objects.requireNonNull(service);
    this.mapper = Objects.requireNonNull(mapper);
  }

  @Override
  public int order() {
    return IDEMPOTENCY;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> R handle(Invocation<R> invocation, Next<R> next) {
    if (!(invocation.request() instanceof IdempotentCommand<?> command)) {
      return next.proceed();
    }
    byte[] body = mapper.writeValueAsBytes(command);
    String fingerprint = IdempotencyService.fingerprint("COMMAND", invocation.name(), body);
    IdempotencyService.Decision decision =
        service.begin(
            scope(invocation.name(), RequestContext.get()), command.idempotencyKey(), fingerprint);
    if (decision instanceof IdempotencyService.Replay replay) {
      StoredResponse stored = replay.response();
      // Los comandos bloqueantes almacenan JSON null; un cuerpo vacío representa una finalización
      // reactiva vacía.
      byte[] json = stored.body().length == 0 ? JSON_NULL : stored.body();
      return (R) mapper.readValue(json, invocation.resultType());
    }
    IdempotencyService.Proceed execution = (IdempotencyService.Proceed) decision;
    try (var lease = service.startLease(execution)) {
      try {
        R result = next.proceed();
        byte[] out = result == null ? JSON_NULL : mapper.writeValueAsBytes(result);
        if (!service.complete(
            execution, new StoredResponse(200, Map.of("Content-Type", "application/json"), out))) {
          LOG.warn("Idempotency completion could not be confirmed");
        }
        return result;
      } catch (RuntimeException failure) {
        if (failure instanceof NoEffect) service.releaseWithoutEffect(execution);
        else service.fail(execution);
        throw failure;
      }
    }
  }

  /**
   * Alcance de la clave de un comando: el nombre del comando más el llamador (emisor, tenant,
   * cliente, subject), de modo que dos llamadores que comparten una clave nunca reciben los
   * resultados del otro.
   */
  static String scope(String commandName, @Nullable RequestMetadata caller) {
    String operation = "cqrs:" + commandName;
    return caller == null
        ? IdempotencyService.scope(operation, null, null, null, null)
        : IdempotencyService.scope(
            operation, caller.issuer(), caller.tenantId(), caller.clientId(), caller.subject());
  }
}
