package pe.axiz.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import pe.axiz.codegen.asyncapi.AsyncApiDiff;
import pe.axiz.codegen.asyncapi.AsyncApiDocument;
import pe.axiz.codegen.openapi.ContractDiff;
import pe.axiz.codegen.output.SafePaths;
import pe.axiz.codegen.project.ProjectManifest;

/**
 * Synchronizes payloads without overwriting implementations or accepting a changed published vN.
 */
public final class AsyncContractSync {
  public static final String SNAPSHOT = ".axiz/asyncapi.snapshot.yaml";

  /** Files created, proposals awaiting review, compatibility changes and whether sync applied. */
  public record Result(
      List<String> created,
      List<String> proposed,
      List<ContractDiff.Change> changes,
      boolean applied) {
    public Result {
      created = List.copyOf(created);
      proposed = List.copyOf(proposed);
      changes = List.copyOf(changes);
    }
  }

  public Result sync(Path directory) throws IOException {
    ProjectManifest manifest = ProjectManifest.read(directory);
    String contractPath = manifest.asyncapi();
    if (contractPath == null)
      throw new IllegalArgumentException("The project has no AsyncAPI contract");
    String source =
        Files.readString(SafePaths.target(directory, contractPath), StandardCharsets.UTF_8);
    AsyncApiDocument current = AsyncApiDocument.parse(source);
    if (!manifest.isService())
      throw new IllegalArgumentException("AsyncAPI synchronization applies to services only");
    if (!current.consumed().isEmpty()
        && pe.axiz.codegen.model.Catalog.load()
            .resolve(
                manifest.capabilities(),
                pe.axiz.codegen.model.Catalog.store(manifest.persistence(), manifest.stack()))
            .stream()
            .noneMatch(
                capability -> capability.starter().equals("axiz-messaging-spring-boot-starter")))
      throw new IllegalArgumentException(
          "Consuming AsyncAPI events requires a messaging capability: axiz add messaging-sqs (or messaging-kafka)");
    Path snapshot = SafePaths.target(directory, SNAPSHOT);
    List<ContractDiff.Change> changes =
        Files.exists(snapshot)
            ? AsyncApiDiff.compare(AsyncApiDocument.parse(Files.readString(snapshot)), current)
            : List.of();
    if (ContractDiff.hasBreaking(changes)) return new Result(List.of(), List.of(), changes, false);
    var payloads =
        EventPayloads.generate(
            manifest.basePackage(), manifest.groupId(), manifest.structure(), current);
    var adapters =
        EventAdapters.generate(
            manifest.basePackage(),
            manifest.groupId(),
            manifest.structure(),
            manifest.stack(),
            current);
    Map<String, byte[]> generated = new java.util.LinkedHashMap<>(payloads);
    generated.putAll(adapters);
    List<String> created = new ArrayList<>();
    List<String> proposed = new ArrayList<>();
    // Validate every destination before any write, including potential proposal files.
    for (String path : generated.keySet()) {
      SafePaths.target(directory, path);
      SafePaths.target(directory, path + ContractSync.PROPOSAL);
    }
    for (var entry : generated.entrySet()) {
      Path target = SafePaths.target(directory, entry.getKey());
      if (!Files.exists(target)) {
        SafePaths.write(directory, entry.getKey(), entry.getValue(), false);
        created.add(entry.getKey());
      } else if (payloads.containsKey(entry.getKey())
          && !Arrays.equals(Files.readAllBytes(target), entry.getValue())) {
        String proposal = entry.getKey() + ContractSync.PROPOSAL;
        SafePaths.write(directory, proposal, entry.getValue(), true);
        proposed.add(proposal);
      }
    }
    // Advance the generation snapshot only after all payload proposals have been written.
    SafePaths.write(directory, SNAPSHOT, source.getBytes(StandardCharsets.UTF_8), true);
    return new Result(created, proposed, changes, true);
  }
}
