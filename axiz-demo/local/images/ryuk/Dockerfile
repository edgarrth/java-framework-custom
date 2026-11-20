# Ryuk 0.14.0 with the maintained Moby SDK and an inspectable Go binary (no UPX).
FROM golang:1.27.4-alpine3.24@sha256:70bd7ddea8b6cbe5d6943cde279d6b67feb10cc924fb9100d1d8757c1575b280 AS builder
WORKDIR /src
RUN apk add --no-cache patch=2.8.1-r0 curl \
 && curl -fLsS --retry 3 -o /tmp/ryuk.tar.gz https://codeload.github.com/testcontainers/moby-ryuk/tar.gz/refs/tags/0.14.0 \
 && printf '%s\n' '8753af202acbfbd9c4f14b2fb85e2171c4cf053b836ccab8d78faa1a45d77fbb  /tmp/ryuk.tar.gz' | sha256sum -c - \
 && tar -xzf /tmp/ryuk.tar.gz --strip-components=1 -C /src
COPY go.mod go.sum sdk.patch ./
RUN patch -p1 < sdk.patch && go mod download && go mod verify \
 && CGO_ENABLED=0 go test -run 'Test_loadConfig|Test_newReaper_Run|TestAbortedClient|TestShutdownSignal' ./... \
 && CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -o /ryuk .
FROM cgr.dev/chainguard/wolfi-base@sha256:7135c6a5e9f29b2542aaefee63d474973312bfdea95555963aef0e648210e4d2 AS certificates
FROM scratch
COPY --from=certificates /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /ryuk /ryuk
COPY LICENSE /LICENSE
# Development/test lifecycle controller: it must access the runner's Docker socket,
# whose owning group varies by host. Docker access itself grants daemon-level control.
# Never deploy Ryuk with application workloads or mount the daemon socket into them.
USER 0:0
ENTRYPOINT ["/ryuk"]
