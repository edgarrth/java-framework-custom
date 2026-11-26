FROM golang:1.27.4-alpine3.24@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280 AS builder
RUN apk add --no-cache curl patch bash
ENV CGO_ENABLED=0 GOMAXPROCS=2
WORKDIR /src
FROM builder AS grafana
RUN curl -fLsS --retry 3 https://codeload.github.com/grafana/grafana/tar.gz/refs/tags/v12.4.15 -o /tmp/source.tar.gz \
 && echo 'e4ec779cdda4c2cb9f8c550fde952f485440b751efeaf02159e7da25ace1bc07  /tmp/source.tar.gz' | sha256sum -c - \
 && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src
COPY grafana-dependencies.patch /tmp/dependencies.patch
RUN patch -p1 < /tmp/dependencies.patch
RUN go build -trimpath -buildvcs=false -tags oss -ldflags "-X main.version=12.4.15" -o /out/grafana ./pkg/cmd/grafana
RUN go mod verify
FROM builder AS loki
RUN curl -fLsS --retry 3 https://codeload.github.com/grafana/loki/tar.gz/refs/tags/v3.7.10 -o /tmp/source.tar.gz \
 && echo '52e1a821de73d634a152e6737f7a4629dae830240015a2d6f244ccaaf3b13357  /tmp/source.tar.gz' | sha256sum -c - \
 && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src
RUN go build -mod=readonly -trimpath -buildvcs=false -tags netgo -ldflags "-X github.com/grafana/loki/v3/pkg/util/build.Version=3.7.10" -o /out/loki ./cmd/loki
RUN go mod verify
FROM builder AS tempo
RUN curl -fLsS --retry 3 https://codeload.github.com/grafana/tempo/tar.gz/refs/tags/v2.10.10 -o /tmp/source.tar.gz \
 && echo '9a4ecf9f2d9c245704965b2844a35d6769e0ed97fdfc3d44663f90d307c5d466  /tmp/source.tar.gz' | sha256sum -c - \
 && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src
COPY tempo-dependencies.patch /tmp/dependencies.patch
RUN patch -p1 < /tmp/dependencies.patch
RUN go build -mod=readonly -trimpath -buildvcs=false -tags netgo -ldflags "-X main.Version=2.10.10" -o /out/tempo ./cmd/tempo
RUN go mod verify
FROM builder AS prometheus
RUN curl -fLsS --retry 3 https://codeload.github.com/prometheus/prometheus/tar.gz/refs/tags/v3.16.0 -o /tmp/source.tar.gz \
 && echo '2709a3bd43f9d15e0cecfc33ab473d011e2a69ab29265b91398cc1b8561403c0  /tmp/source.tar.gz' | sha256sum -c - \
 && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src
COPY prometheus-dependencies.patch /tmp/dependencies.patch
RUN patch -p1 < /tmp/dependencies.patch
RUN curl -fLsS --retry 3 https://github.com/prometheus/prometheus/releases/download/v3.16.0/prometheus-web-ui-3.16.0.tar.gz -o /tmp/assets.tar.gz \
 && echo '000a45231f6dd22a5703218aad3ee11da125c8a44d1824a27670416c07531001  /tmp/assets.tar.gz' | sha256sum -c - \
 && tar -xzf /tmp/assets.tar.gz -C web/ui \
 && bash scripts/compress_assets.sh
RUN go build -mod=readonly -trimpath -buildvcs=false -tags netgo,builtinassets -ldflags "-X github.com/prometheus/common/version.Version=3.16.0" -o /out/prometheus ./cmd/prometheus
RUN go mod verify
FROM builder AS collector
COPY collector-main.go /src/main.go
COPY collector-main_others.go /src/main_others.go
COPY collector-main_windows.go /src/main_windows.go
COPY collector-components.go /src/components.go
COPY collector-go.mod /src/go.mod
COPY collector-go.sum /src/go.sum
RUN go build -mod=readonly -trimpath -buildvcs=false -o /out/otelcol . && go mod verify
FROM grafana/grafana@sha256:2894082f6cf7081aefdc1389265c04000812309208c190d75e8c65f7a30e4b7e AS frontend
FROM cgr.dev/chainguard/wolfi-base@sha256:7135c6a5e9f29b2542aaefee63d474973312bfdea95555963aef0e648210e4d2
RUN apk upgrade --no-cache && apk add --no-cache bash curl tzdata \
 && mkdir -p /data/grafana /data/loki /data/tempo /data/prometheus /otel-lgtm/grafana/conf/provisioning/datasources /otel-lgtm/grafana/conf/provisioning/dashboards /otel-lgtm/grafana/conf/provisioning/plugins /otel-lgtm/grafana/conf/provisioning/alerting /otel-lgtm/axiz-dashboards \
 && chown -R 10001:10001 /data /otel-lgtm
COPY --from=frontend /usr/share/grafana/public /otel-lgtm/grafana/public
COPY --from=frontend /usr/share/grafana/conf/defaults.ini /otel-lgtm/grafana/conf/defaults.ini
COPY --from=grafana /out/grafana /usr/local/bin/grafana
COPY --from=grafana /src/LICENSE /licenses/grafana-LICENSE
COPY --from=loki /out/loki /usr/local/bin/loki
COPY --from=loki /src/LICENSE /licenses/loki-LICENSE
COPY --from=tempo /out/tempo /usr/local/bin/tempo
COPY --from=tempo /src/LICENSE /licenses/tempo-LICENSE
COPY --from=prometheus /out/prometheus /usr/local/bin/prometheus
COPY --from=prometheus /src/LICENSE /licenses/prometheus-LICENSE
COPY --from=collector /out/otelcol /usr/local/bin/otelcol
COPY collector-LICENSE /licenses/collector-LICENSE
COPY start.sh /otel-lgtm/start.sh
COPY grafana.ini loki.yaml tempo.yaml prometheus.yaml collector.yaml /otel-lgtm/
COPY datasources.yaml /otel-lgtm/grafana/conf/provisioning/datasources/axiz.yaml
LABEL pe.axiz.go-sources-sha256="bfcdbdbef926580b633d0ef6383be3e59e125d646b33672c892dcf101c2b2244"
USER 10001:10001
WORKDIR /otel-lgtm
EXPOSE 3000 4317 4318
HEALTHCHECK --interval=5s --timeout=3s --start-period=90s --retries=12 CMD curl -fsS http://127.0.0.1:3000/api/health && curl -fsS http://127.0.0.1:9090/-/ready && curl -fsS http://127.0.0.1:3100/ready && curl -fsS http://127.0.0.1:3200/ready
ENTRYPOINT ["/bin/bash", "/otel-lgtm/start.sh"]
