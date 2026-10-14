# axiz-docs

migration guides and the base Grafana dashboards. Published per framework version with `mike`.

```bash
python3 -m pip install -r requirements.txt
mkdocs serve                     # http://127.0.0.1:8000
mkdocs build --strict            # what CI runs: broken links and anchors fail
```

| Path | Contents |
|---|---|
| `docs/` | Hand-written guides plus generated reference pages (marked `GENERATED`) |
| `scripts/cli-help.txt` | `axiz <command> --help` of the released CLI |
| `scripts/dashboards.py` | Generates `dashboards/*.json` |
| `dashboards/` | Base Grafana dashboards: events, RED per service, messaging, dependencies |
| `.github/workflows/docs.yaml` | Lint + strict build on PRs; on tags, regenerate from the framework at the same tag and publish the version |

regenerate.
