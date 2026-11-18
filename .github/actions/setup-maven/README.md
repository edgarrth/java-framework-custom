# setup-maven

Configures JDK 25 and a GitHub Packages repository. Credentials remain references to
`PACKAGES_USERNAME` / `PACKAGES_TOKEN` in the job environment; credential values are never stored
in settings. Builds supply read-only credentials. Isolated deploy jobs publish with their own
temporary `GITHUB_TOKEN` and `packages: write` permission.

For a candidate, first run `consume-build` with the explicit producer run, attempt and the
manifest SHA-256 obtained outside the artifact. Pass its verified bundle as `candidate-bundle`,
together with `candidate-manifest-sha256` and `candidate-source-repository`.

Candidate mode revalidates all Maven bytes, verifies a separate copy and makes it read-only.
The active `axiz-github` profile orders the `axiz-candidate` copy before the remote registry. GitHub Packages does not mirror Maven Central.
Maven's local repository is a new empty directory under `RUNNER_TEMP` for every invocation, and
setup-java/GraalVM Maven caching is disabled. Original build evidence is never modified.
The action outputs the isolated local and candidate repository paths.

Run the regression suite using the requirements of `../build-manifest`:

```bash
python -m unittest discover -s .github/actions/setup-maven -p 'test_*.py'
```
