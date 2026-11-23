package pe.axiz.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;

/**
 * Ejecuta el Bash generado contra una frontera de comandos AWS con estado, incluidas fallas
 * inyectadas.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(60)
class LocalInitScriptTest {
  private static final String AWS =
      """
      #!/usr/bin/env bash
      set -euo pipefail
      service=$1 operation=$2; shift 2
      printf '%s %s\\n' "$service" "$operation" >> "$FAKE_STATE/calls"
      if [[ "${FAIL_OPERATION:-}" == "$service $operation" ]]; then
        echo 'An error occurred (AccessDeniedException): injected failure' >&2
        exit 77
      fi
      case "$service $operation" in
        's3api list-buckets')
          if [[ -f "$FAKE_STATE/bucket" ]]; then echo local-bucket; else echo None; fi ;;
        's3 mb') touch "$FAKE_STATE/bucket" ;;
        'dynamodb describe-table'|'dynamodb create-table')
          while [[ "$1" != --table-name ]]; do shift; done
          resource="$FAKE_STATE/table-$2"
          if [[ "$operation" == create-table ]]; then
            if [[ -f "$resource" ]]; then echo ResourceInUseException >&2; exit 1; fi
            touch "$resource"
          elif [[ ! -f "$resource" ]]; then
            echo 'An error occurred (ResourceNotFoundException): absent table' >&2; exit 1
          fi ;;
        'sqs create-queue'|'sns create-topic') : ;;
        'kms describe-key')
          if [[ -f "$FAKE_STATE/alias" ]]; then cat "$FAKE_STATE/alias"
          else echo 'An error occurred (NotFoundException): absent alias' >&2; exit 1; fi ;;
        'kms create-key')
          count=0
          if [[ -f "$FAKE_STATE/key-count" ]]; then count=$(cat "$FAKE_STATE/key-count"); fi
          count=$((count + 1)); echo "$count" > "$FAKE_STATE/key-count"; echo "key-$count" ;;
        'kms create-alias')
          while [[ "$1" != --target-key-id ]]; do shift; done
          printf '%s' "$2" > "$FAKE_STATE/alias" ;;
        *) echo "Unexpected command: $service $operation" >&2; exit 2 ;;
      esac
      """;

  private static String init() {
    return ProjectGenerator.withDefaults()
        .generate(
            Fixtures.request(
                "business",
                Structure.HEXAGONAL,
                Stack.SERVLET,
                "dynamodb",
                List.of(
                    "storage-s3",
                    "crypto",
                    "messaging-sqs",
                    "messaging-sns",
                    "audit-sqs",
                    "idempotency-dynamodb")))
        .text("local/localstack/init.sh");
  }

  private static Path fixture(Path root) throws IOException {
    Files.createDirectories(root);
    Path bin = Files.createDirectories(root.resolve("bin"));
    Path command = Files.writeString(bin.resolve("awslocal"), AWS);
    assertTrue(command.toFile().setExecutable(true));
    Files.writeString(root.resolve("init.sh"), init());
    return Files.createDirectories(root.resolve("state"));
  }

  private static int run(Path root, Path state, String failure) throws Exception {
    var builder = new ProcessBuilder("bash", root.resolve("init.sh").toString());
    builder.environment().put("PATH", root.resolve("bin") + ":" + System.getenv("PATH"));
    builder.environment().put("FAKE_STATE", state.toString());
    builder.environment().put("FAIL_OPERATION", failure);
    builder.redirectErrorStream(true).redirectOutput(root.resolve("output.log").toFile());
    var process = builder.start();
    try {
      assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
      return process.exitValue();
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void repeatedInitializationReusesResourcesAndTheSameKmsKey(@TempDir Path root) throws Exception {
    Path state = fixture(root);
    assertEquals(0, run(root, state, ""));
    String alias = Files.readString(state.resolve("alias"));
    assertEquals(0, run(root, state, ""));
    assertEquals(alias, Files.readString(state.resolve("alias")));
    assertEquals("1", Files.readString(state.resolve("key-count")).trim());
    var calls = Files.readAllLines(state.resolve("calls"));
    assertEquals(1L, calls.stream().filter("dynamodb create-table"::equals).count());
    assertEquals(1L, calls.stream().filter("s3 mb"::equals).count());
    assertEquals(1L, calls.stream().filter("kms create-alias"::equals).count());
    assertTrue(calls.containsAll(List.of("sqs create-queue", "sns create-topic")));
  }

  @Test
  void unexpectedAwsErrorsFailInsteadOfBeingTreatedAsMissingOrExistingResources(@TempDir Path root)
      throws Exception {
    for (String operation :
        List.of(
            "s3api list-buckets",
            "s3 mb",
            "dynamodb describe-table",
            "dynamodb create-table",
            "sqs create-queue",
            "sns create-topic",
            "kms describe-key",
            "kms create-key",
            "kms create-alias")) {
      Path scenario = root.resolve(operation.replace(' ', '-'));
      Path state = fixture(scenario);
      assertNotEquals(0, run(scenario, state, operation), operation);
      assertTrue(Files.readString(scenario.resolve("output.log")).contains("injected failure"));
    }
  }
}
