# setup-maven

Configures JDK 25 and a Nexus mirror. Credentials remain references to
`NEXUS_USERNAME` / `NEXUS_PASSWORD` in the job environment; credential values are never stored
in settings. Builds supply read-only credentials. Deploy workflows use separate destination
credentials.

For a candidate, first run `consume-build` with the explicit producer run, attempt and the
manifest SHA-256 obtained outside the artifact. Pass its verified bundle as `candidate-bundle`,
together with `candidate-manifest-sha256` and `candidate-source-repository`.

Candidate mode revalidates all Maven bytes, verifies a separate copy and makes it read-only.
The mirror becomes `*,!axiz-candidate`; an active `axiz-candidate` profile resolves from the copy.
Maven's local repository is a new empty directory under `RUNNER_TEMP` for every invocation, and
setup-java/GraalVM Maven caching is disabled. Original build evidence is never modified.
The action outputs the isolated local and candidate repository paths.

Run the regression suite using the requirements of `../build-manifest`:

```bash
python -m unittest discover -s actions/setup-maven -p 'test_*.py'
```
