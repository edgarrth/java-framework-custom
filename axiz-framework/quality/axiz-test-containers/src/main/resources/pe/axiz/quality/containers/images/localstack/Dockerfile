FROM localstack/localstack@sha256:3ebc37595918b8accb852f8048fef2aff047d465167edd655528065b07bc364a AS distribution
FROM cgr.dev/chainguard/wolfi-base@sha256:7135c6a5e9f29b2542aaefee63d474973312bfdea95555963aef0e648210e4d2
RUN apk add --no-cache python-3.13=3.13.18_git20261115-r1 openjdk-25-jre=25.0.5.1-r1 bash=5.3-r14 curl=8.23.0-r3 libffi=3.8.1-r0 sqlite-libs=3.54.1-r1 libstdc++=16.2.0-r5 tzdata=2026f-r1 iproute2=7.3.0-r1 \
 && mkdir -p /usr/local/bin /usr/lib/localstack/java /var/lib/localstack /etc/localstack/init \
 && ln -s /usr/bin/python3.13 /usr/local/bin/python \
 && ln -s /usr/bin/python3.13 /usr/local/bin/python3
COPY --from=distribution /opt/code/localstack /opt/code/localstack
COPY --from=distribution /usr/lib/localstack/dynamodb-local /usr/lib/localstack/dynamodb-local
COPY --from=distribution /usr/local/bin/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY --from=distribution /usr/local/bin/awslocal /usr/local/bin/awslocal
COPY --from=distribution /etc/localstack /etc/localstack
ENV PATH=/opt/code/localstack/.venv/bin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    PYTHONUNBUFFERED=1 LANG=C.UTF-8 LOCALSTACK_BUILD_VERSION=4.14.0 \
    SERVICES=dynamodb,s3,sqs,sns,kms,secretsmanager,ssm,sts,iam
# The source image pins unchanged dependencies; every updated wheel/sdist is hash locked.
COPY requirements.lock /opt/axiz/requirements.lock
COPY build-requirements.lock /opt/axiz/build-requirements.lock
RUN python -m pip install --no-cache-dir --no-deps --require-hashes -r /opt/axiz/build-requirements.lock \
 && python -m pip install --no-cache-dir --no-deps --no-build-isolation --require-hashes -r /opt/axiz/requirements.lock \
 && python -m pip uninstall -y kclpy-ext \
 && python -m pip check \
 && python -m pip uninstall -y pip
# DynamoDB Local is the only Java service in this dedicated Axiz emulator.
RUN sed -i 's/self.java_version = "21"/self.java_version = "25"/' /opt/code/localstack/localstack-core/localstack/services/dynamodb/packages.py \
 && sed -i '/JAVA_VERSIONS = {/a\    "25": "25.0.5+1",' /opt/code/localstack/localstack-core/localstack/packages/java.py \
 && ln -s /usr/lib/jvm/java-25-openjdk /usr/lib/localstack/java/25
RUN set -eu; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/guava-33.5.0-jre.jar https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar; \
    printf '%s\n' 'b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/guava-33.5.0-jre.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-annotations-2.20.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar; \
    printf '%s\n' '21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-annotations-2.20.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-core-2.20.1.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.3/jackson-core-2.22.3.jar; \
    printf '%s\n' '8a501126a385b25841915d839508f8a66e2a0dbc8a6709d055ef3b3e852b094c  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-core-2.20.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-databind-2.20.1.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.22.3/jackson-databind-2.22.3.jar; \
    printf '%s\n' '556db5439e206114346043f68d200497dc96a0bca62a360a81784092ebd0e0a9  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-databind-2.20.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-datatype-jsr310-2.20.1.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.22.3/jackson-datatype-jsr310-2.22.3.jar; \
    printf '%s\n' 'ec7052ee48c9d873bb8148338a643f02cf89c01d035dd219411473030d6d2b22  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jackson-datatype-jsr310-2.20.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-alpn-client-12.0.31.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-alpn-client/12.0.36/jetty-alpn-client-12.0.36.jar; \
    printf '%s\n' '6b0e03b545fa49fc82cf3149dafb3849e6e0827b3756c31b0e1f5324d86816d2  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-alpn-client-12.0.31.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-client-12.0.31.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-client/12.0.36/jetty-client-12.0.36.jar; \
    printf '%s\n' '1e188b18e8ff6b60ca743e9c9cc39c35f8b6b1b6a7dc07cbdd06800672a1ee02  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-client-12.0.31.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-http-12.0.31.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-http/12.0.36/jetty-http-12.0.36.jar; \
    printf '%s\n' '2b5c6ee1303aa979b99afb27e3c602096c344e06da9bdc0057938d1c47f8f1f2  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-http-12.0.31.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-io-12.0.31.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-io/12.0.36/jetty-io-12.0.36.jar; \
    printf '%s\n' 'b191d45e3c48711cc784d509a8dec50112816af392b2b7c76d9e42324b5064da  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-io-12.0.31.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-server-12.0.31.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-server/12.0.36/jetty-server-12.0.36.jar; \
    printf '%s\n' 'f0eb8d1e71d67d0f937c45752546cf479ba55ad3135cf600d3bf723f52cd53fe  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-server-12.0.31.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-util-12.0.31.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-util/12.0.36/jetty-util-12.0.36.jar; \
    printf '%s\n' 'e16c145dd761168133d1505b6f7cf05343f5e3e3ff03333757c8a6312896ab48  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/jetty-util-12.0.31.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-1.5.32.jar https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.25/kotlin-stdlib-1.9.25.jar; \
    printf '%s\n' 'f9cdcdbff1f5de85380ae526977e683726c2aa42db1ed6e6e50ae89e496e95fd  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-1.5.32.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-common-1.6.21.jar https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-common/1.9.25/kotlin-stdlib-common-1.9.25.jar; \
    printf '%s\n' '8202e7f032c8dc6698d00c62867701546a7c12c4731ebddfb8eec0788e2eeee4  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-common-1.6.21.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-jdk7-1.5.32.jar https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.9.25/kotlin-stdlib-jdk7-1.9.25.jar; \
    printf '%s\n' 'fb5373dd761b4e93e3f538c5e853bba38a71143a181536e8f193ed6e4eddb3b8  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-jdk7-1.5.32.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-jdk8-1.5.32.jar https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.9.25/kotlin-stdlib-jdk8-1.9.25.jar; \
    printf '%s\n' 'f94fdf78390ce9be30383bf039c5a935caea33b11f037fc7f86bbcee19287e5a  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/kotlin-stdlib-jdk8-1.5.32.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-all-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-all/4.1.137.Final/netty-all-4.1.137.Final.jar; \
    printf '%s\n' '3c21d10662148be5e1dc37adea6866faf0e43096a2174c2ef12a4553b45c3af2  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-all-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-buffer-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-buffer/4.1.137.Final/netty-buffer-4.1.137.Final.jar; \
    printf '%s\n' 'f474b14c7734f15e0540394cb6f39d67777b7581a42919e4ac89d253d4efd929  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-buffer-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec/4.1.137.Final/netty-codec-4.1.137.Final.jar; \
    printf '%s\n' '9987b6a660b0a6b1f0d791485dae33180b3d1c63687c006fe6d3fd025e9e3798  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-dns-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-dns/4.1.137.Final/netty-codec-dns-4.1.137.Final.jar; \
    printf '%s\n' '2d9a33ce41bd9f6f607df95039e42518a1fca16f83c9bbfe72beb57df6e2489f  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-dns-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-haproxy-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-haproxy/4.1.137.Final/netty-codec-haproxy-4.1.137.Final.jar; \
    printf '%s\n' '5ffa58b94c5ac48fb7e41843c18acf7a268e90b5b85423987829c9291ce3a5df  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-haproxy-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-http-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-http/4.1.137.Final/netty-codec-http-4.1.137.Final.jar; \
    printf '%s\n' '0535bb5a736472bef5c948d15eb273c4ab9f796656fc7c5d6b982ad92bddbd49  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-http-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-http2-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-http2/4.1.137.Final/netty-codec-http2-4.1.137.Final.jar; \
    printf '%s\n' '576ddcfb51b78e86f145c9a52b60e500b1a1d5a257cafec8aa5416ffba044b49  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-http2-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-memcache-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-memcache/4.1.137.Final/netty-codec-memcache-4.1.137.Final.jar; \
    printf '%s\n' '3ce5bf2eb871266e93c0bbec050ff814a8641ff4c3b1a21d3fe87523d7940339  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-memcache-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-mqtt-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-mqtt/4.1.137.Final/netty-codec-mqtt-4.1.137.Final.jar; \
    printf '%s\n' '4c60d6633cb6f547d30fbb6c94d10f037f59f09261214b70c8763cc66d2941d7  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-mqtt-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-redis-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-redis/4.1.137.Final/netty-codec-redis-4.1.137.Final.jar; \
    printf '%s\n' 'b6952e57e0237c68d20f972695111d93148a6559fee94d2919280ccea984c2f8  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-redis-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-smtp-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-smtp/4.1.137.Final/netty-codec-smtp-4.1.137.Final.jar; \
    printf '%s\n' 'ebf50d91aa43124af1a7318cf3bdb9a84a3d1a7f5c092ff377bc6f012e83f41c  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-smtp-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-socks-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-socks/4.1.137.Final/netty-codec-socks-4.1.137.Final.jar; \
    printf '%s\n' '4a6190e08988c058bfeef2b5965e71946439c771abf721c68f4039b4e7b023e8  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-socks-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-stomp-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-stomp/4.1.137.Final/netty-codec-stomp-4.1.137.Final.jar; \
    printf '%s\n' 'e01124c4dc99d37124ae41a3893226217ba628ad1661424c5a5525959b9fbb63  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-stomp-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-xml-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-codec-xml/4.1.137.Final/netty-codec-xml-4.1.137.Final.jar; \
    printf '%s\n' 'f4488c827ae8224ef86594851ae1778d9efe1f02d02808d774d21720cc61ed68  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-codec-xml-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-common-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-common/4.1.137.Final/netty-common-4.1.137.Final.jar; \
    printf '%s\n' 'd31926b01adcc07af86f5e27b81b6d6c115df17d366e835d1fc3f5a1924e7e52  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-common-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-handler-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-handler/4.1.137.Final/netty-handler-4.1.137.Final.jar; \
    printf '%s\n' 'd0e4c6ee4779f59f6ab2fb5d388e4f57147c82270164b37945764bb9bda96a44  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-handler-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-handler-proxy-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-handler-proxy/4.1.137.Final/netty-handler-proxy-4.1.137.Final.jar; \
    printf '%s\n' '843de10c4cef34cbc1a5344090f1b57db532fbebf141559862da675f6b656df4  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-handler-proxy-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-handler-ssl-ocsp-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-handler-ssl-ocsp/4.1.137.Final/netty-handler-ssl-ocsp-4.1.137.Final.jar; \
    printf '%s\n' '6a85347e2d598d28da934cc4d8f68b9990ab7052d8366876b820adfa9a5a81df  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-handler-ssl-ocsp-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-resolver/4.1.137.Final/netty-resolver-4.1.137.Final.jar; \
    printf '%s\n' 'b4cf2aeedd9fc7c8c439bbfe574f63cfe5b83392e88bbc07ca0e8424b7cff955  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns/4.1.137.Final/netty-resolver-dns-4.1.137.Final.jar; \
    printf '%s\n' '898d63ca62ed68ff46543c5344f969424b417f1e8d082fe2bb0181ba10144194  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-classes-macos-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-classes-macos/4.1.137.Final/netty-resolver-dns-classes-macos-4.1.137.Final.jar; \
    printf '%s\n' '92d0a542954475f563ea58221d864884ba6753c50c1f17985b1045e990bf094c  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-classes-macos-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-native-macos-4.1-osx-aarch_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-native-macos/4.1.137.Final/netty-resolver-dns-native-macos-4.1.137.Final-osx-aarch_64.jar; \
    printf '%s\n' '777e1e9f0337e04388e9f9bd9ff5270e607423be72306b381fd380418b06b9ac  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-native-macos-4.1-osx-aarch_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-native-macos-4.1-osx-x86_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-resolver-dns-native-macos/4.1.137.Final/netty-resolver-dns-native-macos-4.1.137.Final-osx-x86_64.jar; \
    printf '%s\n' 'bc8a5616ab33f0e640bc6174eea770ccfca8ed0e9891ff3bb3ae638255bc006e  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-resolver-dns-native-macos-4.1-osx-x86_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport/4.1.137.Final/netty-transport-4.1.137.Final.jar; \
    printf '%s\n' '6251adc2a2921572382732a2db188d4f4f2251fd6ebb49c5d44bbf33d6bfb1a7  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-classes-epoll-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-classes-epoll/4.1.137.Final/netty-transport-classes-epoll-4.1.137.Final.jar; \
    printf '%s\n' '55049554b799dc8e53bf234fffa36e001f7b5b65c32994b9bd59fc6356f1c52f  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-classes-epoll-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-classes-kqueue-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-classes-kqueue/4.1.137.Final/netty-transport-classes-kqueue-4.1.137.Final.jar; \
    printf '%s\n' '88d0ac216f9c7be690a9d0157778570d6f4c61f885863da1fccec7fcaee35557  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-classes-kqueue-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-epoll-4.1-linux-aarch_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.137.Final/netty-transport-native-epoll-4.1.137.Final-linux-aarch_64.jar; \
    printf '%s\n' '82fb63a2dc90c0bddb81e04065a1c06fb4c5be722ad5d64b46491daf8b6f9a01  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-epoll-4.1-linux-aarch_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-epoll-4.1-linux-x86_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-epoll/4.1.137.Final/netty-transport-native-epoll-4.1.137.Final-linux-x86_64.jar; \
    printf '%s\n' '185db6053e0e9573d36fa4eb6567b26139afaa4daa7bf5a068e661568304542b  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-epoll-4.1-linux-x86_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-kqueue-4.1-osx-aarch_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-kqueue/4.1.137.Final/netty-transport-native-kqueue-4.1.137.Final-osx-aarch_64.jar; \
    printf '%s\n' '6745bb54be0a9105c40c045dcc322b107ac16abf82716963fd64ee921dc5408a  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-kqueue-4.1-osx-aarch_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-kqueue-4.1-osx-x86_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-kqueue/4.1.137.Final/netty-transport-native-kqueue-4.1.137.Final-osx-x86_64.jar; \
    printf '%s\n' '29f96e4f350df9467e5cf49e83c0be27a094a6a1f6dfc1bf0e0be9af84333729  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-kqueue-4.1-osx-x86_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-unix-common-4.1-linux-x86_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.137.Final/netty-transport-native-unix-common-4.1.137.Final-linux-x86_64.jar; \
    printf '%s\n' 'ec7b265a52261f87a9dad9cfdf85e5bb3bd8efd4fddcdba9e70ee843ff5c3667  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-unix-common-4.1-linux-x86_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-unix-common-4.1-osx-x86_64.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.137.Final/netty-transport-native-unix-common-4.1.137.Final-osx-x86_64.jar; \
    printf '%s\n' '80a3a40f8c9a552b749efa788bec0c96209279667c3f9a80ea312c69cf472ad7  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-unix-common-4.1-osx-x86_64.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-unix-common-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-native-unix-common/4.1.137.Final/netty-transport-native-unix-common-4.1.137.Final.jar; \
    printf '%s\n' '8056e7637f9948f953314894cf995ab664711b66c73c4cd5b593ae84f0b1048c  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-native-unix-common-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-rxtx-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-rxtx/4.1.137.Final/netty-transport-rxtx-4.1.137.Final.jar; \
    printf '%s\n' '6422a703bf80bfd15ff9ef84564b47dc5f83e4d31ebf5431f0301304d376aea9  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-rxtx-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-sctp-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-sctp/4.1.137.Final/netty-transport-sctp-4.1.137.Final.jar; \
    printf '%s\n' 'ff0c2b8999b97c2d078f9685558c6d3ca2207df2e35d30551d29a659bce7d2c5  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-sctp-4.1.jar' | sha256sum -c -; \
    curl -fLsS --retry 3 -o /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-udt-4.1.jar https://repo.maven.apache.org/maven2/io/netty/netty-transport-udt/4.1.137.Final/netty-transport-udt-4.1.137.Final.jar; \
    printf '%s\n' '28a7fd7a93e51565008747e8929af9f1182f4494cbdd01fe54d5d79081f51b38  /usr/lib/localstack/dynamodb-local/2/DynamoDBLocal_lib/netty-transport-udt-4.1.jar' | sha256sum -c -;

# Application code and bundled executables stay immutable to the runtime user.
RUN addgroup -g 1000 localstack \
 && adduser -D -u 1000 -G localstack -h /home/localstack localstack \
 && mkdir -p /var/lib/localstack/.cache /home/localstack/.cache \
 && chown -R 1000:1000 /var/lib/localstack /home/localstack \
 && chown -R 0:0 /opt/code/localstack /usr/lib/localstack \
 && chmod -R a-w /opt/code/localstack /usr/lib/localstack
ENV XDG_CACHE_HOME=/var/lib/localstack/.cache
USER 1000:1000
EXPOSE 4566
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
