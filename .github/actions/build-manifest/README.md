# Build identity

`build-manifest.json` describes one verified build. Maven libraries and services have separate
strict schemas. `parameters_sha256` uses RFC 8785; the manifest hash describes its exact bytes.
Deploy receives the expected hash through the trusted build output or approval, independently
of the downloaded bundle. A checksum inside the archive does not establish trust.

A bundle contains the canonical manifest, its cosign keyless signature bundle, gate evidence,
SBOM and provenance. Libraries also include `maven/` with every staged file and `SHA256SUMS`.
Nexus generates repository metadata; promotion must not overwrite its existing version index.

`consume-build` verifies the explicit producer run and attempt, the allowed immutable workflow
release, the signature identity, the source repository and commit, every required gate and all
staged bytes. Missing or expired artifacts fail. The verified bundle is read-only.

The signature policy uses the reusable workflow identity documented by
[Fulcio](https://github.com/sigstore/fulcio/blob/main/docs/oidc.md) and
[cosign blob verification](https://docs.sigstore.dev/cosign/verifying/verify/).

Run the regression suite in an isolated Python environment:

```sh
python3 -m venv /tmp/axiz-manifest-tests
/tmp/axiz-manifest-tests/bin/pip install -r actions/build-manifest/requirements.txt
/tmp/axiz-manifest-tests/bin/python -m unittest discover -s actions/build-manifest -p 'test_*.py'
```
