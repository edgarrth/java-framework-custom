FROM valkey/valkey:8.1.8-alpine@sha256:94365b275456ae14621001c03556c732b1d93a0cdeacc317d1bdd52eba680885
RUN apk upgrade --no-cache \
    && apk add --no-cache libcrypto3=3.5.9-r0 libssl3=3.5.9-r0 musl=1.2.5-r24 \
        musl-utils=1.2.5-r24 openssl=3.5.9-r0 setpriv=2.41.6-r1 zlib=1.4-r0
