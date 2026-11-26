FROM valkey/valkey:8.1.8-alpine@sha256:94365b275456ae14621001c03556c732b1d93a0cdeacc317d1bdd52eba680885
RUN apk upgrade --no-cache \
    && apk add --no-cache libcrypto3 libssl3 musl \
        musl-utils openssl setpriv zlib

USER valkey
