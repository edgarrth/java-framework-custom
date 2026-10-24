package pe.axiz.framework.cqrs.spring;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pe.axiz.core.commons.RequestContext;
import pe.axiz.core.commons.RequestMetadata;
import pe.axiz.core.idempotency.IdempotencyService;
import pe.axiz.core.idempotency.StoredResponse;
import pe.axiz.framework.cqrs.IdempotentCommand;
import pe.axiz.framework.cqrs.Invocation;
import pe.axiz.framework.cqrs.PipelineBehavior;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotent commands: the first result is stored (JSON) and returned again for the same key, the
 * same command and the same caller; a different command with the same key is a 422 problem. It runs
 * after the authorization behavior, so a replay is only returned to a caller that is still allowed
 * to run the command.
 */
public class IdempotencyBehavior implements PipelineBehavior {

  private static final byte[] JSON_NULL = "null".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyBehavior.class);

  private final IdempotencyService service;
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
    if (decision instanceof IdempotencyService.Replay(StoredResponse stored)) {
      // the stored result is JSON: "null" for a command without result (Command<Void>); an empty
      // body was written by earlier versions for the same case
      byte[] json = stored.body().length == 0 ? JSON_NULL : stored.body();
      return (R) mapper.readValue(json, invocation.resultType());
    }
    IdempotencyService.Proceed execution = (IdempotencyService.Proceed) decision;
    R result;
    try {
      result = next.proceed();
    } catch (RuntimeException e) {
      service.fail(execution);
      throw e;
    }
    byte[] out = result == null ? JSON_NULL : mapper.writeValueAsBytes(result);
    if (!service.complete(
        execution, new StoredResponse(200, Map.of("Content-Type", "application/json"), out))) {
      LOG.warn(
          "Idempotency key lost before completion (lock expired); result not stored: {}",
          execution.key());
    }
    return result;
  }

  /**
   * Scope of a command key: the command name plus the caller (issuer, tenant, client, subject), so
   * two callers sharing a key never receive each other's results.
   */
  static String scope(String commandName, @Nullable RequestMetadata caller) {
    String operation = "cqrs:" + commandName;
    return caller == null
        ? IdempotencyService.scope(operation, null, null, null, null)
        : IdempotencyService.scope(
            operation, caller.issuer(), caller.tenantId(), caller.clientId(), caller.subject());
  }
}
