package pe.axiz.structure.layered.spring;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.function.Function;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import pe.axiz.core.api.PageRequest;
import pe.axiz.core.api.PageResponse;
import pe.axiz.structure.layered.CrudService;

/**
 * REST endpoints of a simple resource on top of a {@link CrudService}. Subclasses add
 * {@code @RestController} and {@code @RequestMapping("/v1/<resources>")}; errors become RFC 9457
 * problems through the errors starter. Paging uses {@code page}, {@code size} and {@code sort}.
 *
 * <p>Declare route authorization through axiz.security.authorization.rules; per-object checks
 * belong to the service. Entity classes must never be used as request or response types.
 *
 * @param <RequestT> accepted request fields
 * @param <ResponseT> public response
 * @param <I> id type
 */
public abstract class CrudController<RequestT, ResponseT, I> {

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
  public ResponseEntity<Void> delete(@PathVariable("id") I id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
