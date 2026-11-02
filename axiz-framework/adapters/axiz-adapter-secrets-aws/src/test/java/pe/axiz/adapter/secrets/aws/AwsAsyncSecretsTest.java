package pe.axiz.adapter.secrets.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

class AwsAsyncSecretsTest {
  @Test
  void structuredFieldsRemainValidJsonAndMalformedSecretsFailWithoutValueDiagnostics() {
    assertEquals(
        "{\"nested\":true}",
        SecretsManagerResolver.JsonField.extract(
                "{\"credentials\":{\"nested\":true}}", "credentials")
            .orElseThrow());
    var raw = new Manager();
    var lookup =
        new SecretsManagerAsyncResolver(raw, "")
            .resolve("credentials#password")
            .toCompletableFuture();
    String sensitive = "sensitive-value-that-is-not-json";
    raw.pending.complete(GetSecretValueResponse.builder().secretString(sensitive).build());
    var failure = assertThrows(CompletionException.class, lookup::join).getCause();
    assertTrue(failure instanceof IllegalStateException);
    assertTrue(!failure.toString().contains(sensitive));
    assertTrue(failure.getCause() == null, "parser diagnostics must not expose value characters");
  }

  @Test
  void secretsManagerKeepsPrefixesAndJsonFieldSemantics() {
    var raw = new Manager();
    var resolver = new SecretsManagerAsyncResolver(raw, "prod/");
    var password = resolver.require("payments#password").toCompletableFuture();
    assertEquals("prod/payments", raw.request.secretId());
    raw.pending.complete(
        GetSecretValueResponse.builder()
            .secretString("{\"password\":\"sensitive\",\"port\":5432}")
            .build());
    assertEquals("sensitive", password.join().reveal());
    raw.pending = new CompletableFuture<>();
    var port = resolver.require("payments#port").toCompletableFuture();
    raw.pending.complete(GetSecretValueResponse.builder().secretString("{\"port\":5432}").build());
    assertEquals("5432", port.join().reveal());
    raw.pending = new CompletableFuture<>();
    var binaryOnly = resolver.resolve("binary").toCompletableFuture();
    raw.pending.complete(GetSecretValueResponse.builder().build());
    assertTrue(binaryOnly.join().isEmpty());
  }

  @Test
  void onlyMissingSecretsBecomeAbsenceAndPermissionErrorsKeepTheirCause() {
    var raw = new Manager();
    var resolver = new SecretsManagerAsyncResolver(raw, "");
    var missing = resolver.resolve("missing").toCompletableFuture();
    raw.pending.completeExceptionally(
        ResourceNotFoundException.builder().message("missing").build());
    assertTrue(missing.join().isEmpty());
    raw.pending = new CompletableFuture<>();
    var denied = resolver.resolve("protected").toCompletableFuture();
    var failure = SecretsManagerException.builder().statusCode(403).message("denied").build();
    raw.pending.completeExceptionally(failure);
    assertSame(failure, assertThrows(CompletionException.class, denied::join).getCause());
  }

  @Test
  void parameterStoreDecryptsSecureStringsAndDistinguishesAbsenceFromOutages() {
    var raw = new Parameters();
    var resolver = new ParameterStoreAsyncResolver(raw, "/prod/payments");
    var found = resolver.require("password").toCompletableFuture();
    assertEquals("/prod/payments/password", raw.request.name());
    assertTrue(raw.request.withDecryption());
    raw.pending.complete(
        GetParameterResponse.builder().parameter(p -> p.value("sensitive")).build());
    assertEquals("sensitive", found.join().reveal());
    raw.pending = new CompletableFuture<>();
    var fieldAlias = resolver.require("database#password").toCompletableFuture();
    assertEquals("/prod/payments/database/password", raw.request.name());
    raw.pending.complete(GetParameterResponse.builder().parameter(p -> p.value("aliased")).build());
    assertEquals("aliased", fieldAlias.join().reveal());
    raw.pending = new CompletableFuture<>();
    var missing = resolver.resolve("missing").toCompletableFuture();
    raw.pending.completeExceptionally(
        ParameterNotFoundException.builder().message("missing").build());
    assertTrue(missing.join().isEmpty());
    raw.pending = new CompletableFuture<>();
    var failed = resolver.resolve("failed").toCompletableFuture();
    var failure = new IllegalStateException("closed transport");
    raw.pending.completeExceptionally(failure);
    assertSame(failure, assertThrows(CompletionException.class, failed::join).getCause());
  }

  @Test
  void defaultRequirePreservesExactSdkCancellationForBothSources() {
    var manager = new Manager();
    assertTrue(
        new SecretsManagerAsyncResolver(manager, "")
            .require("key")
            .toCompletableFuture()
            .cancel(true));
    assertTrue(manager.pending.isCancelled());
    var parameters = new Parameters();
    assertTrue(
        new ParameterStoreAsyncResolver(parameters, "/prod/")
            .require("key")
            .toCompletableFuture()
            .cancel(true));
    assertTrue(parameters.pending.isCancelled());
  }

  private static final class Manager implements SecretsManagerAsyncClient {
    GetSecretValueRequest request = GetSecretValueRequest.builder().build();
    CompletableFuture<GetSecretValueResponse> pending = new CompletableFuture<>();

    public CompletableFuture<GetSecretValueResponse> getSecretValue(GetSecretValueRequest request) {
      this.request = request;
      return pending;
    }

    public String serviceName() {
      return "secretsmanager";
    }

    public void close() {}
  }

  private static final class Parameters implements SsmAsyncClient {
    GetParameterRequest request = GetParameterRequest.builder().build();
    CompletableFuture<GetParameterResponse> pending = new CompletableFuture<>();

    public CompletableFuture<GetParameterResponse> getParameter(GetParameterRequest request) {
      this.request = request;
      return pending;
    }

    public String serviceName() {
      return "ssm";
    }

    public void close() {}
  }
}
