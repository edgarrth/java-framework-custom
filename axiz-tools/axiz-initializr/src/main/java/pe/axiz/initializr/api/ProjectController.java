package pe.axiz.initializr.api;

import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pe.axiz.codegen.RequestValidator;
import pe.axiz.codegen.openapi.LintFinding;
import pe.axiz.codegen.output.GeneratedProject;
import pe.axiz.initializr.config.InitializrProperties;
import pe.axiz.initializr.service.GenerationService;

/** Generation API: validate, lint and generate (ZIP). */
@RestController
public class ProjectController {

  static final MediaType ZIP = MediaType.parseMediaType("application/zip");

  private final GenerationService generation;
  private final InitializrProperties properties;

  public ProjectController(GenerationService generation, InitializrProperties properties) {
    this.generation = generation;
    this.properties = properties;
  }

  /** Generates the project and returns it as a ZIP; 422 with every problem otherwise. */
  @PostMapping(
      path = "/v1/projects",
      produces = {"application/zip", "application/problem+json"})
  public ResponseEntity<byte[]> generate(@Valid @RequestBody ProjectRequestBody body) {
    GeneratedProject project =
        generation.generate(body.toRequest(generation.catalog(), properties.frameworkVersion()));
    ByteArrayOutputStream zip = new ByteArrayOutputStream();
    try {
      project.writeZip(zip);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return ResponseEntity.ok()
        .contentType(ZIP)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(project.rootName() + ".zip")
                .build()
                .toString())
        .body(zip.toByteArray());
  }

  /** Checks a request without generating it. */
  @PostMapping("/v1/projects/validate")
  public Map<String, Object> validate(@Valid @RequestBody ProjectRequestBody body) {
    RequestValidator.Result r =
        generation.validate(body.toRequest(generation.catalog(), properties.frameworkVersion()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("valid", r.valid());
    out.put("problems", r.problems());
    out.put("findings", findings(r.findings()));
    return out;
  }

  /** Lints a contract with the corporate rules. */
  @PostMapping(
      path = "/v1/contracts/lint",
      consumes = {"application/yaml", "application/json", "text/plain"})
  public Map<String, Object> lint(@RequestBody String contract) {
    List<LintFinding> findings = generation.lint(contract);
    return Map.of(
        "valid", findings.stream().noneMatch(LintFinding::error), "findings", findings(findings));
  }

  static List<Map<String, Object>> findings(List<LintFinding> findings) {
    return findings.stream()
        .map(
            f ->
                Map.<String, Object>of(
                    "rule",
                    f.rule(),
                    "severity",
                    f.severity(),
                    "location",
                    f.location(),
                    "message",
                    f.message()))
        .toList();
  }
}
