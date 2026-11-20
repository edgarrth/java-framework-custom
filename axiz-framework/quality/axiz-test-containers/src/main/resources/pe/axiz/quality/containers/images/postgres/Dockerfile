FROM postgres:17.12-alpine@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7
# su-exec performs setgroups/setgid/setuid/exec without shipping a Go runtime.
# Keep upstream initialization and signal propagation; the image starts as postgres.
RUN apk add --no-cache su-exec=0.3-r0 libcrypto3=3.5.9-r1 libssl3=3.5.9-r1 \
    && test "$(grep -c 'exec gosu postgres' /usr/local/bin/docker-entrypoint.sh)" = 1 \
    && sed -i 's/exec gosu postgres/exec su-exec postgres/' /usr/local/bin/docker-entrypoint.sh \
    && rm /usr/local/bin/gosu \
    && su-exec postgres id \
    && ! command -v gosu

USER postgres
