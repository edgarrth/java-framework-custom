FROM apache/kafka:4.2.2@sha256:1213eb3943d551e5ed1fca7a4e109001cee35770b66a02a0c37a8964efe09b69
USER root
# Se parchean los paquetes de la distribución y las bibliotecas afectadas; se conservan los metadatos de paquetes para SCA.
RUN apk upgrade --no-cache \
    && apk add --no-cache libcrypto3 libssl3 openssl gnutls
# Se verifica cada descarga antes de eliminar su predecesora. Compatible con el builder clásico de Docker.
# Las descargas fijadas por checksum se intentan en Maven Central y en su espejo oficial de Google
# (Central limita por IP con 429); el checksum se verifica igual sea cual sea el origen.
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/kafka/libs/jackson-core-2.21.7.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.21.7/jackson-core-2.21.7.jar || wget -q -O /opt/kafka/libs/jackson-core-2.21.7.jar https://maven-central.storage-download.googleapis.com/maven2/com/fasterxml/jackson/core/jackson-core/2.21.7/jackson-core-2.21.7.jar; } && break; rm -f /opt/kafka/libs/jackson-core-2.21.7.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' '8c5623b98f32d5f7e1287ff61ed50e08ab7b11381e991fff89327faabba42261  /opt/kafka/libs/jackson-core-2.21.7.jar' | sha256sum -c - \
    && test -f /opt/kafka/libs/jackson-core-2.21.6.jar && rm /opt/kafka/libs/jackson-core-2.21.6.jar \
    && chown 1000:1000 /opt/kafka/libs/jackson-core-2.21.7.jar && chmod 0644 /opt/kafka/libs/jackson-core-2.21.7.jar
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/kafka/libs/jackson-databind-2.21.7.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.21.7/jackson-databind-2.21.7.jar || wget -q -O /opt/kafka/libs/jackson-databind-2.21.7.jar https://maven-central.storage-download.googleapis.com/maven2/com/fasterxml/jackson/core/jackson-databind/2.21.7/jackson-databind-2.21.7.jar; } && break; rm -f /opt/kafka/libs/jackson-databind-2.21.7.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' '1290c2795e93e8a6861a6c4d9ff0d844d32f5ea178362cb2721edf5561e828b1  /opt/kafka/libs/jackson-databind-2.21.7.jar' | sha256sum -c - \
    && test -f /opt/kafka/libs/jackson-databind-2.21.6.jar && rm /opt/kafka/libs/jackson-databind-2.21.6.jar \
    && chown 1000:1000 /opt/kafka/libs/jackson-databind-2.21.7.jar && chmod 0644 /opt/kafka/libs/jackson-databind-2.21.7.jar
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/kafka/libs/snappy-java-1.1.10.9.jar https://repo.maven.apache.org/maven2/org/xerial/snappy/snappy-java/1.1.10.9/snappy-java-1.1.10.9.jar || wget -q -O /opt/kafka/libs/snappy-java-1.1.10.9.jar https://maven-central.storage-download.googleapis.com/maven2/org/xerial/snappy/snappy-java/1.1.10.9/snappy-java-1.1.10.9.jar; } && break; rm -f /opt/kafka/libs/snappy-java-1.1.10.9.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' '35228628442e4b9797681c7cbd3b26594f6da3379159dbd0cb07094ed3aece0d  /opt/kafka/libs/snappy-java-1.1.10.9.jar' | sha256sum -c - \
    && test -f /opt/kafka/libs/snappy-java-1.1.10.7.jar && rm /opt/kafka/libs/snappy-java-1.1.10.7.jar \
    && chown 1000:1000 /opt/kafka/libs/snappy-java-1.1.10.9.jar && chmod 0644 /opt/kafka/libs/snappy-java-1.1.10.9.jar
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/kafka/libs/lz4-java-1.11.4.jar https://repo.maven.apache.org/maven2/at/yawk/lz4/lz4-java/1.11.4/lz4-java-1.11.4.jar || wget -q -O /opt/kafka/libs/lz4-java-1.11.4.jar https://maven-central.storage-download.googleapis.com/maven2/at/yawk/lz4/lz4-java/1.11.4/lz4-java-1.11.4.jar; } && break; rm -f /opt/kafka/libs/lz4-java-1.11.4.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' '58c8e0b813960d2a248e050c353baea73139b48a2ffc55382d42c69707e17325  /opt/kafka/libs/lz4-java-1.11.4.jar' | sha256sum -c - \
    && test -f /opt/kafka/libs/lz4-java-1.11.2.jar && rm /opt/kafka/libs/lz4-java-1.11.2.jar \
    && chown 1000:1000 /opt/kafka/libs/lz4-java-1.11.4.jar && chmod 0644 /opt/kafka/libs/lz4-java-1.11.4.jar
USER appuser
