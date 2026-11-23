FROM postgres:17.12-alpine@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7
# su-exec realiza setgroups/setgid/setuid/exec sin incluir un runtime de Go.
# Se mantienen la inicialización upstream y la propagación de señales; la imagen arranca como postgres.
RUN apk add --no-cache su-exec=0.3-r0 libcrypto3=3.5.9-r1 libssl3=3.5.9-r1 libxml2=2.13.9-r3 \
    && test "$(grep -c 'exec gosu postgres' /usr/local/bin/docker-entrypoint.sh)" = 1 \
    && sed -i 's/exec gosu postgres/exec su-exec postgres/' /usr/local/bin/docker-entrypoint.sh \
    && rm /usr/local/bin/gosu \
    && su-exec postgres id \
    && ! command -v gosu

USER postgres
