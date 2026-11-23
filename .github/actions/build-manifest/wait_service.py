#!/usr/bin/env python3
"""Observa Argo CD mediante su API de solo lectura y exige las fuentes inmutables aprobadas."""
import argparse
import json
import os
import re
import time
import urllib.request
from pathlib import Path

from manifest import read_json
from service_deploy import desired, synced


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Argo CD API redirects are forbidden")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--record-sha256", required=True)
    parser.add_argument("--server", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.:-]*", args.server) or args.timeout < 1:
        raise ValueError("Argo CD server must be an explicit HTTPS hostname with an optional port")
    prepared = read_json(args.prepared)
    expected = desired(prepared["manifest"], args.manifest_sha256, args.environment, prepared["values"],
                       args.record_sha256, int(os.environ["GITHUB_RUN_ID"]))
    app = prepared["manifest"]["parameters"]["service"] + "-" + args.environment
    opener = urllib.request.build_opener(NoRedirect())
    url = "https://" + args.server + "/api/v1/applications/" + app
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        request = urllib.request.Request(url + "?refresh=normal", headers={"Authorization": "Bearer " + os.environ["ARGOCD_AUTH_TOKEN"]})
        with opener.open(request, timeout=30) as response:
            state = json.load(response)
        if synced(state, expected):
            print("Argo CD reports the approved chart, values revision and image Synced and Healthy")
            return
        status = state.get("status", {})
        print("Waiting for approved sources:", status.get("sync", {}).get("status"), status.get("health", {}).get("status"), flush=True)
        time.sleep(5)
    raise TimeoutError("Argo CD did not confirm the approved immutable deployment before the deadline")


if __name__ == "__main__":
    main()
