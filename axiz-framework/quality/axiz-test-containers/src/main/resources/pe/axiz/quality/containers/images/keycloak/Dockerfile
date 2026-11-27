FROM quay.io/keycloak/keycloak:26.8.2@sha256:c68e286cacd87b942ac1939a0a1481461109d6acd3a57dc496969a319a76bbf0 AS distribution
FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
ENV KC_RUN_IN_CONTAINER=true
# El heap local de pruebas y demo se acota con independencia de la RAM disponible en el host de Docker.
ENV JAVA_OPTS_KC_HEAP="-Xms64m -Xmx512m"
RUN apk upgrade --no-cache && apk add --no-cache zlib libcrypto3 libssl3 openssl && addgroup -g 1000 keycloak && adduser -D -u 1000 -G keycloak keycloak
COPY --from=distribution --chown=1000:1000 /opt/keycloak /opt/keycloak
# Las descargas fijadas por checksum se intentan en Maven Central y en su espejo oficial de Google
# (Central limita por IP con 429); el checksum se verifica igual sea cual sea el origen.
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/keycloak/lib/lib/main/com.google.guava.guava-33.7.2-jre.jar https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar || wget -q -O /opt/keycloak/lib/lib/main/com.google.guava.guava-33.7.2-jre.jar https://maven-central.storage-download.googleapis.com/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar; } && break; rm -f /opt/keycloak/lib/lib/main/com.google.guava.guava-33.7.2-jre.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' 'b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c  /opt/keycloak/lib/lib/main/com.google.guava.guava-33.7.2-jre.jar' | sha256sum -c - \
    && rm /opt/keycloak/lib/lib/main/com.google.guava.guava-33.6.0-jre.jar
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.67.0.jar https://repo.maven.apache.org/maven2/io/opentelemetry/opentelemetry-api/1.67.0/opentelemetry-api-1.67.0.jar || wget -q -O /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.67.0.jar https://maven-central.storage-download.googleapis.com/maven2/io/opentelemetry/opentelemetry-api/1.67.0/opentelemetry-api-1.67.0.jar; } && break; rm -f /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.67.0.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' '538d1dff2f9a37aecbc0ef98fd17273a3affb058e734e6763a3a07a38a5fdc59  /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.67.0.jar' | sha256sum -c - \
    && rm /opt/keycloak/lib/lib/main/io.opentelemetry.opentelemetry-api-1.62.0.jar
RUN for attempt in 1 2 3 4 5 6; do { wget -q -O /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.11.jar https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11.jar || wget -q -O /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.11.jar https://maven-central.storage-download.googleapis.com/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.11/mariadb-java-client-3.5.11.jar; } && break; rm -f /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.11.jar; [ "$attempt" -lt 6 ] || exit 1; sleep $((attempt * 15)); done \
    && printf '%s\n' '42604178b9e57ec08ee0035079961516f086e455d0141f0b22c6eff29fb2fff4  /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.11.jar' | sha256sum -c - \
    && rm /opt/keycloak/lib/lib/main/org.mariadb.jdbc.mariadb-java-client-3.5.10.jar
# El índice de arranque serializado de Quarkus conserva los nombres de archivo originales de la distribución.
# Los alias apuntan a los JAR corregidos reales; sus metadatos de Maven y sus hashes se mantienen intactos.
WORKDIR /opt/keycloak/lib/lib/main
RUN ln -s com.google.guava.guava-33.7.2-jre.jar com.google.guava.guava-33.6.0-jre.jar \
    && ln -s io.opentelemetry.opentelemetry-api-1.67.0.jar io.opentelemetry.opentelemetry-api-1.62.0.jar \
    && ln -s org.mariadb.jdbc.mariadb-java-client-3.5.11.jar org.mariadb.jdbc.mariadb-java-client-3.5.10.jar
USER 1000:1000
WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build --db=dev-file
EXPOSE 8080
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
