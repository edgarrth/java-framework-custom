# axiz-adapter-aws

AWS identity and transport infrastructure without Micrometer or Spring dependencies.

`identity` contains IRSA policy, credential resources with uniform close and warmed snapshots.
`transport` contains SDK client defaults and bounded asynchronous completions. Identity does not
reference transport. Resource owners release the corresponding `AwsCredentialSource`; its
`provider()` method supplies a non-closing view. Observation and metric bindings are supplied by
`axiz-adapter-observability-aws` and composed by Spring.

Run `./mvnw -pl adapters/axiz-adapter-aws -am verify`.

A credential resource always has `close()`, whether it owns a source or borrows one. Closing a
borrowed resource invalidates that resource's view and preserves the owner's provider. Owned
resources close their provider once. Do not infer ownership from an SDK provider's runtime type.
Spring owns one lazily created default source per application context and hands independent
resources to consumers; capability/service/application providers retain D3 precedence.

The credential plugin transfers its resource through a private closable SDK provider adapter.
The SDK client closes that adapter, which releases only the resource. Calling `provider()`
directly keeps ownership with the caller; that view cannot close the underlying identity.
A failed factory build also releases its credential resource and retains the original failure.
