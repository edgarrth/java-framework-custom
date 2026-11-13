package pe.axiz.initializr.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.codegen.ArtifactGenerator;
import pe.axiz.codegen.model.Archetype;
import pe.axiz.codegen.model.Capability;
import pe.axiz.codegen.model.Extension;
import pe.axiz.codegen.model.Stack;
import pe.axiz.codegen.model.Structure;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.service.GenerationService;

/** What can be generated: archetypes with their defaults and rules, styles and capabilities. */
@RestController
public class MetadataController {

  private final GenerationService generation;
  private final InitializrProperties properties;

  public MetadataController(GenerationService generation, InitializrProperties properties) {
    this.generation = generation;
    this.properties = properties;
  }

  /** Catalog of the initializr (used by the web UI and the CLI). */
  @GetMapping("/v1/metadata")
  public Map<String, Object> metadata() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("frameworkVersion", properties.frameworkVersion());
    out.put(
        "archetypes",
        generation.catalog().archetypes().stream().map(MetadataController::archetype).toList());
    List<Map<String, Object>> structures =
        Arrays.stream(Structure.values())
            .filter(f -> f != Structure.NONE)
            .map(
                f ->
                    Map.<String, Object>of(
                        "id", f.id(),
                        "stacks", f.stacks().stream().map(Stack::id).sorted().toList(),
                        "artifacts", ArtifactGenerator.typesFor(f)))
            .toList();
    out.put("structures", structures);
    out.put(
        "capabilities",
        generation.catalog().capabilities().stream().map(MetadataController::capability).toList());
    return out;
  }

  private static Map<String, Object> archetype(Archetype a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", a.name());
    m.put("role", a.role());
    m.put("kind", a.kind());
    m.put("structures", a.structures().stream().map(Structure::id).toList());
    m.put("extensions", a.extensions().stream().map(Extension::id).toList());
    m.put("stacks", a.stacks().stream().map(Stack::id).toList());
    m.put("persistence", a.persistence());
    m.put("starters", a.starters());
    m.put("deployment", a.deployment());
    m.put("rest", a.rest());
    return m;
  }

  private static Map<String, Object> capability(Capability c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", c.name());
    m.put("description", c.description());
    m.put("starter", c.starter());
    m.put("requires", c.requires());
    m.put("stacks", c.stacks().stream().map(Stack::id).toList());
    m.put("persistence", List.copyOf(c.persistence().keySet()));
    return m;
  }
}
