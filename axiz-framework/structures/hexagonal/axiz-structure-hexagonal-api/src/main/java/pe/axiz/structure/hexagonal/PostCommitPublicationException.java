package pe.axiz.structure.hexagonal;

import pe.axiz.core.commons.tx.CommittedTransactionOutcome;
import pe.axiz.core.errors.BusinessException;
import pe.axiz.core.errors.CoreErrors;

/**
 * Business work committed, but direct event publication did not complete. Delivery may be partial;
 * never repeat the business operation. Use a transactional outbox for guaranteed delivery. Provider
 * messages, event payloads and the business result are deliberately not retained.
 */
public final class PostCommitPublicationException extends BusinessException
    implements CommittedTransactionOutcome {
  public PostCommitPublicationException() {
    super(CoreErrors.POST_COMMIT_PUBLICATION, CoreErrors.POST_COMMIT_PUBLICATION.title());
  }
}
