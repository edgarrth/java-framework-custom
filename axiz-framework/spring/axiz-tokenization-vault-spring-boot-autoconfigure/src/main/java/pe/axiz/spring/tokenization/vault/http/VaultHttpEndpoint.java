package pe.axiz.spring.tokenization.vault.http;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import pe.axiz.core.concurrent.Stages;
import pe.axiz.core.tokenization.SensitiveValue;
import pe.axiz.core.tokenization.Token;
import pe.axiz.core.tokenization.TokenDescriptor;
import pe.axiz.core.tokenization.TokenErrors;
import pe.axiz.core.tokenization.TokenType;
import pe.axiz.core.tokenization.TokenizationResult;
import pe.axiz.core.tokenization.vault.AsyncVaultOperations;
import pe.axiz.core.tokenization.vault.TokenCaller;
import pe.axiz.core.tokenization.vault.VaultOperations;

/**
 * La identidad del invocante se aporta con independencia del DTO; ambas pilas comparten los
 * contratos de salida.
 */
final class VaultHttpEndpoint {
  private VaultHttpEndpoint() {}

  static VaultHttpReply execute(
      VaultOperations engine, TokenCaller caller, VaultValueRequest request) {
    return switch (request.operation()) {
      case TOKENIZE ->
          tokenized(
              engine.tokenize(
                  caller,
                  request.type(),
                  request.value(),
                  request.domain(),
                  request.operationId()));
      case LOOKUP ->
          found(
              request.type(),
              engine.lookup(
                  caller, request.type(), request.value(), request.domain(), request.purpose()));
      case DESCRIBE ->
          describe(engine.describe(caller, request.type(), request.token(), request.domain()));
      case DELETE ->
          VaultHttpReply.deleted(
              engine.delete(caller, request.type(), request.token(), request.domain()));
      case DETOKENIZE -> {
        try (var value =
            engine.detokenize(
                caller, request.type(), request.token(), request.domain(), request.purpose())) {
          yield new RevealedValueResponse(request.type(), value).write();
        }
      }
    };
  }

  static CompletionStage<VaultHttpReply> execute(
      AsyncVaultOperations engine, TokenCaller caller, VaultValueRequest request) {
    return Stages.call(
        () ->
            switch (request.operation()) {
              case TOKENIZE ->
                  Stages.consume(
                      engine.tokenize(
                          caller,
                          request.type(),
                          request.value(),
                          request.domain(),
                          request.operationId()),
                      VaultHttpEndpoint::tokenized,
                      ignored -> {},
                      VaultHttpReply::close);
              case LOOKUP ->
                  Stages.consume(
                      engine.lookup(
                          caller,
                          request.type(),
                          request.value(),
                          request.domain(),
                          request.purpose()),
                      found -> found(request.type(), found),
                      ignored -> {},
                      VaultHttpReply::close);
              case DESCRIBE ->
                  Stages.consume(
                      engine.describe(caller, request.type(), request.token(), request.domain()),
                      VaultHttpEndpoint::describe,
                      ignored -> {},
                      VaultHttpReply::close);
              case DELETE ->
                  Stages.consume(
                      engine.delete(caller, request.type(), request.token(), request.domain()),
                      VaultHttpReply::deleted,
                      ignored -> {},
                      VaultHttpReply::close);
              case DETOKENIZE ->
                  Stages.consume(
                      engine.detokenize(
                          caller,
                          request.type(),
                          request.token(),
                          request.domain(),
                          request.purpose()),
                      value -> new RevealedValueResponse(request.type(), value).write(),
                      SensitiveValue::close,
                      VaultHttpReply::close);
            });
  }

  private static VaultHttpReply tokenized(TokenizationResult result) {
    return VaultHttpReply.json(
        result.created() ? 201 : 200,
        Map.of(
            "token",
            result.token().reveal(),
            "type",
            result.type().value(),
            "created",
            result.created(),
            "metadata",
            result.metadata()));
  }

  private static VaultHttpReply found(TokenType type, Optional<Token> found) {
    Token token = found.orElseThrow(() -> TokenErrors.failure(TokenErrors.NOT_FOUND));
    return VaultHttpReply.json(200, Map.of("token", token.reveal(), "type", type.value()));
  }

  private static VaultHttpReply describe(Optional<TokenDescriptor> found) {
    var result = found.orElseThrow(() -> TokenErrors.failure(TokenErrors.NOT_FOUND));
    return VaultHttpReply.json(
        200,
        Map.of(
            "token",
            result.token().reveal(),
            "type",
            result.type().value(),
            "metadata",
            result.metadata()));
  }

  // Solo este límite autorizado del motor puede construir la respuesta que revela valores. Sin
  // códec global.
  private record RevealedValueResponse(TokenType type, SensitiveValue value) {
    VaultHttpReply write() {
      return VaultHttpReply.json(200, Map.of("type", type.value(), "value", value.reveal()));
    }

    @Override
    public String toString() {
      return "RevealedValueResponse[****]";
    }
  }
}
