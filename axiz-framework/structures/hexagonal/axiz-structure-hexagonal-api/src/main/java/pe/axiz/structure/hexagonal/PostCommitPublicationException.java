package pe.axiz.structure.hexagonal;

import pe.axiz.core.commons.tx.CommittedTransactionOutcome;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;

/**
 * El trabajo de negocio hizo commit, pero la publicación directa de eventos no terminó. La entrega
 * puede ser parcial; nunca se repite la operación de negocio. Para una entrega garantizada se usa
 * un outbox transaccional. Los mensajes del proveedor, los payloads de los eventos y el resultado
 * de negocio no se retienen de forma deliberada.
 */
public final class PostCommitPublicationException extends BusinessException
    implements CommittedTransactionOutcome {
  public PostCommitPublicationException() {
    super(CoreErrors.POST_COMMIT_PUBLICATION, CoreErrors.POST_COMMIT_PUBLICATION.title());
  }
}
