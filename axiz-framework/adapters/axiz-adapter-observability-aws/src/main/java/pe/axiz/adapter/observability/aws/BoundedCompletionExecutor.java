package pe.axiz.adapter.observability.aws;

import pe.axiz.core.commons.BoundedCallbackExecutor;

/** Owned AWS callback workers using the common bounded admission policy. */
final class BoundedCompletionExecutor extends BoundedCallbackExecutor {
  BoundedCompletionExecutor() {
    super(
        2,
        256,
        Thread.ofPlatform().daemon().name("axiz-aws-future-", 0).factory(),
        "AWS completion");
  }
}
