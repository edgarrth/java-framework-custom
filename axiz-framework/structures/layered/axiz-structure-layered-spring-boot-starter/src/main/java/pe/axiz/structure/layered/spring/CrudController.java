package pe.axiz.structure.layered.spring;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.PageResponse;
import pe.axiz.core.errors.FieldViolation;
import pe.axiz.core.errors.ValidationException;
import pe.axiz.structure.layered.CrudService;

/**
 * Endpoints REST de un recurso simple sobre un {@link CrudService}. Las subclases agregan
 * {@code @RestController} y {@code @RequestMapping("/v1/<resources>")}; los errores se convierten
 * en problemas RFC 9457 mediante el starter de errores. La paginación usa {@code page}, {@code
 * size} y {@code sort}.
 *
 * <p>La autorización de rutas se declara mediante axiz.security.authorization.rules; las
 * verificaciones por objeto corresponden al servicio. Las clases de entidad nunca deben usarse como
 * tipos de petición o de respuesta.
 *
 * @param <RequestT> campos de petición aceptados
 * @param <ResponseT> respuesta pública
 * @param <I> tipo del id
 */
public abstract class CrudController<RequestT, ResponseT, I> {

  private static final Pattern STRONG_VERSION = Pattern.compile("\"[^\"\\r\\n\\\\]{1,128}\"");

  private final CrudService<RequestT, ResponseT, I> service;
  private final Function<ResponseT, I> idOf;

  protected CrudController(
      CrudService<RequestT, ResponseT, I> service, Function<ResponseT, I> idOf) {
    this.service = service;
    this.idOf = idOf;
  }

  @GetMapping("/{id}")
  public ResponseT get(@PathVariable("id") I id) {
    return service.get(id);
  }

  @GetMapping
  public PageResponse<ResponseT> list(PageRequest page) {
    return PageResponse.from(service.list(page));
  }

  @PostMapping
  public ResponseEntity<ResponseT> create(@Valid @RequestBody RequestT resource) {
    ResponseT created = service.create(resource);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(idOf.apply(created))
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  @PutMapping("/{id}")
  public ResponseT update(@PathVariable("id") I id, @Valid @RequestBody RequestT resource) {
    return service.update(id, resource);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable("id") I id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) {
    if (!STRONG_VERSION.matcher(ifMatch).matches())
      throw new ValidationException(
          List.of(
              FieldViolation.of("If-Match", "StrongVersion", "One quoted version is required")));
    service.delete(id, ifMatch.substring(1, ifMatch.length() - 1));
    return ResponseEntity.noContent().build();
  }
}
