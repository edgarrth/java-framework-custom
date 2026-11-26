# Generado por .github/scripts/update_wiremock_image.py desde libraries.lock.json.
# Solo se extrae el launcher Run.class upstream; no se distribuye ningún JAR de dependencias sombreado.
FROM wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3 AS distribution
FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS patched-runtime
RUN apk upgrade --no-cache && apk add --no-cache zlib
FROM patched-runtime AS launcher
COPY --from=distribution /var/wiremock/lib/wiremock-standalone.jar /tmp/launcher-source.jar
RUN printf '%s\n' 'd097b19bd483c5038479b13a5c71e9faf8f2f5106584f0c120a7770ab0bdb367  /tmp/launcher-source.jar' | sha256sum -c - \
    && mkdir -p /launcher/wiremock \
    && unzip -p /tmp/launcher-source.jar wiremock/Run.class > /launcher/wiremock/Run.class
FROM patched-runtime
RUN addgroup -g 1000 wiremock && adduser -D -u 1000 -G wiremock wiremock \
    && mkdir -p /var/wiremock/lib /var/wiremock/extensions /home/wiremock/mappings /home/wiremock/__files \
    && chown -R wiremock:wiremock /home/wiremock
COPY --from=launcher /launcher /var/wiremock/launcher
RUN set -eu; \
    wget -q -O /var/wiremock/lib/itu-1.14.0.jar https://repo.maven.apache.org/maven2/com/ethlo/time/itu/1.14.0/itu-1.14.0.jar; \
    printf '%s\n' '5cf40ab0cc77828ab2b875b1f3ecd71c8295d7721933476abc2e08fddcea164a  /var/wiremock/lib/itu-1.14.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-annotations-2.22.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar; \
    printf '%s\n' '21ddb598807d3a51a876704eb979d9296e1c6a6f47ab1826ff88c6d6a127a2d0  /var/wiremock/lib/jackson-annotations-2.22.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-core-2.22.3.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-core/2.22.3/jackson-core-2.22.3.jar; \
    printf '%s\n' '8a501126a385b25841915d839508f8a66e2a0dbc8a6709d055ef3b3e852b094c  /var/wiremock/lib/jackson-core-2.22.3.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-databind-2.22.3.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.22.3/jackson-databind-2.22.3.jar; \
    printf '%s\n' '556db5439e206114346043f68d200497dc96a0bca62a360a81784092ebd0e0a9  /var/wiremock/lib/jackson-databind-2.22.3.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-datatype-jsr310-2.22.3.jar https://repo.maven.apache.org/maven2/com/fasterxml/jackson/datatype/jackson-datatype-jsr310/2.22.3/jackson-datatype-jsr310-2.22.3.jar; \
    printf '%s\n' 'ec7052ee48c9d873bb8148338a643f02cf89c01d035dd219411473030d6d2b22  /var/wiremock/lib/jackson-datatype-jsr310-2.22.3.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/handlebars-4.5.2.jar https://repo.maven.apache.org/maven2/com/github/jknack/handlebars/4.5.2/handlebars-4.5.2.jar; \
    printf '%s\n' '86355b9fb8e79e6648012f013165dd471b405f37f1a770e6d239db7a6916ff78  /var/wiremock/lib/handlebars-4.5.2.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/handlebars-helpers-4.3.1.jar https://repo.maven.apache.org/maven2/com/github/jknack/handlebars-helpers/4.3.1/handlebars-helpers-4.3.1.jar; \
    printf '%s\n' '56f0ad4defb444b038a708a8e0d003692df1b912b91219736a764de88ff7867c  /var/wiremock/lib/handlebars-helpers-4.3.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/error_prone_annotations-2.50.0.jar https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.50.0/error_prone_annotations-2.50.0.jar; \
    printf '%s\n' '4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a  /var/wiremock/lib/error_prone_annotations-2.50.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/failureaccess-1.0.3.jar https://repo.maven.apache.org/maven2/com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar; \
    printf '%s\n' 'cbfc3906b19b8f55dd7cfd6dfe0aa4532e834250d7f080bd8d211a3e246b59cb  /var/wiremock/lib/failureaccess-1.0.3.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/guava-33.7.2-jre.jar https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar; \
    printf '%s\n' 'b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c  /var/wiremock/lib/guava-33.7.2-jre.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar https://repo.maven.apache.org/maven2/com/google/guava/listenablefuture/9999.0-empty-to-avoid-conflict-with-guava/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar; \
    printf '%s\n' 'b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99  /var/wiremock/lib/listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/j2objc-annotations-3.1.jar https://repo.maven.apache.org/maven2/com/google/j2objc/j2objc-annotations/3.1/j2objc-annotations-3.1.jar; \
    printf '%s\n' '84d3a150518485f8140ea99b8a985656749629f6433c92b80c75b36aba3b099b  /var/wiremock/lib/j2objc-annotations-3.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/json-path-2.10.0.jar https://repo.maven.apache.org/maven2/com/jayway/jsonpath/json-path/2.10.0/json-path-2.10.0.jar; \
    printf '%s\n' '890daa95dd3892d34d9fabc27cd5153656e6f369358625c88f4dc7b79cbd6c5a  /var/wiremock/lib/json-path-2.10.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/json-schema-validator-3.0.6.jar https://repo.maven.apache.org/maven2/com/networknt/json-schema-validator/3.0.6/json-schema-validator-3.0.6.jar; \
    printf '%s\n' 'f6f727370bbc53de6d1321710b7e90288774042e69e3bb6992d6801048f56800  /var/wiremock/lib/json-schema-validator-3.0.6.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/commons-fileupload-1.6.0.jar https://repo.maven.apache.org/maven2/commons-fileupload/commons-fileupload/1.6.0/commons-fileupload-1.6.0.jar; \
    printf '%s\n' '9383272c93569afeabedb16923a94a6dc8a5bd7a2f9f83bf326af4ee68434629  /var/wiremock/lib/commons-fileupload-1.6.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/commons-io-2.20.0.jar https://repo.maven.apache.org/maven2/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar; \
    printf '%s\n' 'df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72  /var/wiremock/lib/commons-io-2.20.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jakarta.activation-api-2.1.4.jar https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/2.1.4/jakarta.activation-api-2.1.4.jar; \
    printf '%s\n' 'c9db52100ce6c8aac95cc39075f95720d2e561b11f8051b81c121ad4effd7004  /var/wiremock/lib/jakarta.activation-api-2.1.4.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jakarta.servlet-api-6.1.0.jar https://repo.maven.apache.org/maven2/jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar; \
    printf '%s\n' '8a31f465f3593bf2351531a5c952014eb839da96a605b5825b93dd54714c48c4  /var/wiremock/lib/jakarta.servlet-api-6.1.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jakarta.xml.bind-api-4.0.5.jar https://repo.maven.apache.org/maven2/jakarta/xml/bind/jakarta.xml.bind-api/4.0.5/jakarta.xml.bind-api-4.0.5.jar; \
    printf '%s\n' '5e489b6c874c4119e003ff1403db523ee3a8959ec499f3de29e77245efccf216  /var/wiremock/lib/jakarta.xml.bind-api-4.0.5.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/json-unit-core-2.40.1.jar https://repo.maven.apache.org/maven2/net/javacrumbs/json-unit/json-unit-core/2.40.1/json-unit-core-2.40.1.jar; \
    printf '%s\n' 'adb7e82bff755c8a90c3b7412226f3a72ed4a8bbdb0cdb3726b46905036051dc  /var/wiremock/lib/json-unit-core-2.40.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/accessors-smart-2.6.0.jar https://repo.maven.apache.org/maven2/net/minidev/accessors-smart/2.6.0/accessors-smart-2.6.0.jar; \
    printf '%s\n' '222c9f547bb20a99fc486403a398352d1306fb671b38abd7ecab6401df170e61  /var/wiremock/lib/accessors-smart-2.6.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/json-smart-2.6.0.jar https://repo.maven.apache.org/maven2/net/minidev/json-smart/2.6.0/json-smart-2.6.0.jar; \
    printf '%s\n' '1ae4b561458afb540be8ec5c6dbb4f2e715a319a7ae64854998aaf924770d61b  /var/wiremock/lib/json-smart-2.6.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jopt-simple-5.0.4.jar https://repo.maven.apache.org/maven2/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar; \
    printf '%s\n' 'df26cc58f235f477db07f753ba5a3ab243ebe5789d9f89ecf68dd62ea9a66c28  /var/wiremock/lib/jopt-simple-5.0.4.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/commons-lang3-3.20.0.jar https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.20.0/commons-lang3-3.20.0.jar; \
    printf '%s\n' '69e5c9fa35da7a51a5fd2099dfe56a2d8d32cf233e2f6d770e796146440263f4  /var/wiremock/lib/commons-lang3-3.20.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/commons-text-1.15.0.jar https://repo.maven.apache.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.jar; \
    printf '%s\n' '58d2da30f058512a1e7f914e39241deca4dff5c27a085b4ed2faa9e7208067f6  /var/wiremock/lib/commons-text-1.15.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/httpclient5-5.6.5.jar https://repo.maven.apache.org/maven2/org/apache/httpcomponents/client5/httpclient5/5.6.5/httpclient5-5.6.5.jar; \
    printf '%s\n' 'a10b555f7c85a720d1011a086d679d854b155816cde91a40198cc98c2b0867e8  /var/wiremock/lib/httpclient5-5.6.5.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/httpcore5-5.4.4.jar https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5/5.4.4/httpcore5-5.4.4.jar; \
    printf '%s\n' 'b08e3c3a440fec2029e89980134c7c787dd6796454641ad1eafb81478d7e8b0b  /var/wiremock/lib/httpcore5-5.4.4.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/httpcore5-h2-5.4.4.jar https://repo.maven.apache.org/maven2/org/apache/httpcomponents/core5/httpcore5-h2/5.4.4/httpcore5-h2-5.4.4.jar; \
    printf '%s\n' '9a61441889f9b222ce99581977cc549b4dfca7ac9d2841582c1e57a31fa65b48  /var/wiremock/lib/httpcore5-h2-5.4.4.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-alpn-client-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-alpn-client/12.1.14/jetty-alpn-client-12.1.14.jar; \
    printf '%s\n' 'b19fc6145005c2978f1d886267cea17b2dc2499afd1aa3f3c3d29f6004f338c5  /var/wiremock/lib/jetty-alpn-client-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-alpn-java-client-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-alpn-java-client/12.1.14/jetty-alpn-java-client-12.1.14.jar; \
    printf '%s\n' '24586b24af4563aa3dbfa22a527c5c6aa1a269e488e9d4070c481380eff3ee7f  /var/wiremock/lib/jetty-alpn-java-client-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-alpn-java-server-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-alpn-java-server/12.1.14/jetty-alpn-java-server-12.1.14.jar; \
    printf '%s\n' '0b45e5441e29f4103b3f15fa00d43482a3dfe01e11b58f7504ae631e07606536  /var/wiremock/lib/jetty-alpn-java-server-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-alpn-server-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-alpn-server/12.1.14/jetty-alpn-server-12.1.14.jar; \
    printf '%s\n' '31387db43b221fec36964b553a6cb0310a4f79a9bb3ac3c40379b36b72891e49  /var/wiremock/lib/jetty-alpn-server-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-client-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-client/12.1.14/jetty-client-12.1.14.jar; \
    printf '%s\n' '70db0a8101390818022466da64110aa25e06d78892cc4dce9353d8647c92c2ec  /var/wiremock/lib/jetty-client-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-http-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-http/12.1.14/jetty-http-12.1.14.jar; \
    printf '%s\n' 'a898f3dad99c2505409047d849cac7f652eced5853f9dad711395a26cd681e13  /var/wiremock/lib/jetty-http-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-io-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-io/12.1.14/jetty-io-12.1.14.jar; \
    printf '%s\n' '9a41452f362f5bd96e017f16d6b66d19d2d60a5981eafd3729367c1d535eb5e9  /var/wiremock/lib/jetty-io-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-proxy-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-proxy/12.1.14/jetty-proxy-12.1.14.jar; \
    printf '%s\n' '937f7fa953b26543e6f52d4d8a88133b1eedf317d635d64c5f5aeef879935c86  /var/wiremock/lib/jetty-proxy-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-security-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-security/12.1.14/jetty-security-12.1.14.jar; \
    printf '%s\n' '62d4a246b978adee64d081025546466a05ee7b57b1dd6293d93953a80ba4cbe7  /var/wiremock/lib/jetty-security-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-server-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-server/12.1.14/jetty-server-12.1.14.jar; \
    printf '%s\n' '62a2c691ecb3b60178e7b1a64d498261d31bcef76dd19147b89dcb2c6f784f30  /var/wiremock/lib/jetty-server-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-session-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-session/12.1.14/jetty-session-12.1.14.jar; \
    printf '%s\n' '5b091e6132ba30a56ce872e07cdc0de263163e46f88d5dea99310d3a6d00899a  /var/wiremock/lib/jetty-session-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-util-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-util/12.1.14/jetty-util-12.1.14.jar; \
    printf '%s\n' 'deb2a47bb9659c69076da1020d058c0ededba61dcd1aebccf83fce4719164adc  /var/wiremock/lib/jetty-util-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-xml-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/jetty-xml/12.1.14/jetty-xml-12.1.14.jar; \
    printf '%s\n' '111ee67bbd31b18081d7efbae91a804e8f5485ae5d90cd40445e2228710b0fd3  /var/wiremock/lib/jetty-xml-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-compression-common-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/compression/jetty-compression-common/12.1.14/jetty-compression-common-12.1.14.jar; \
    printf '%s\n' '121a28a3aad2a036c9f97073ddc37f11cb3e9f3da13425f2c08c380ead93a6db  /var/wiremock/lib/jetty-compression-common-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-compression-gzip-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/compression/jetty-compression-gzip/12.1.14/jetty-compression-gzip-12.1.14.jar; \
    printf '%s\n' 'd0994b3a821d2946c1d1c881be4b3997e95a05d7381328d79b7c1b7b042e9186  /var/wiremock/lib/jetty-compression-gzip-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-ee-webapp-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/ee/jetty-ee-webapp/12.1.14/jetty-ee-webapp-12.1.14.jar; \
    printf '%s\n' 'd333fb32fbbd19429ac848b4f94e8b0f688dd9748a5ab59eae707699556274b3  /var/wiremock/lib/jetty-ee-webapp-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-ee10-servlet-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/ee10/jetty-ee10-servlet/12.1.14/jetty-ee10-servlet-12.1.14.jar; \
    printf '%s\n' 'cfea6fa831b8bf119ab97a73271fb61194a678c9ffca0661aaecefde53368eda  /var/wiremock/lib/jetty-ee10-servlet-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-ee10-servlets-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/ee10/jetty-ee10-servlets/12.1.14/jetty-ee10-servlets-12.1.14.jar; \
    printf '%s\n' '8ab17e2054c27704d6c894ac1bd72f9fadf4feed78f89e86176624f60d615a96  /var/wiremock/lib/jetty-ee10-servlets-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-ee10-webapp-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/ee10/jetty-ee10-webapp/12.1.14/jetty-ee10-webapp-12.1.14.jar; \
    printf '%s\n' '950eac43442abbf9a0bdfd230219cd7b0bb784de7edeae97df98ccc19c97b631  /var/wiremock/lib/jetty-ee10-webapp-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-http2-common-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/http2/jetty-http2-common/12.1.14/jetty-http2-common-12.1.14.jar; \
    printf '%s\n' '03afc520c0ae363bc35245588104cc0e53e6a02629839a504fbd8703cb4046d8  /var/wiremock/lib/jetty-http2-common-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-http2-hpack-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/http2/jetty-http2-hpack/12.1.14/jetty-http2-hpack-12.1.14.jar; \
    printf '%s\n' 'e0e3e44729910297d3a3ce1236213db656e24767caeaee79711af72928d75bdf  /var/wiremock/lib/jetty-http2-hpack-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jetty-http2-server-12.1.14.jar https://repo.maven.apache.org/maven2/org/eclipse/jetty/http2/jetty-http2-server/12.1.14/jetty-http2-server-12.1.14.jar; \
    printf '%s\n' 'd5e3421c318f4effd1f95dcd25f2505e06994ee67214608460c4838b7db80451  /var/wiremock/lib/jetty-http2-server-12.1.14.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/hamcrest-3.0.jar https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest/3.0/hamcrest-3.0.jar; \
    printf '%s\n' '5d66b6a4a680755cb6ed7cb104fa7835ef644667586ff0737adeb977c39ecdbc  /var/wiremock/lib/hamcrest-3.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/hamcrest-core-3.0.jar https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/3.0/hamcrest-core-3.0.jar; \
    printf '%s\n' 'b78a3a81692f421cc01fc17ded9a45e9fb6f3949c712f8ec4d01da6b8c06bc6e  /var/wiremock/lib/hamcrest-core-3.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jspecify-1.0.1.jar https://repo.maven.apache.org/maven2/org/jspecify/jspecify/1.0.1/jspecify-1.0.1.jar; \
    printf '%s\n' '070d75f261fe4c5b8202508366715f7f2d4660f88c8ef7e6d3575e48c9683b66  /var/wiremock/lib/jspecify-1.0.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/nashorn-core-15.4.jar https://repo.maven.apache.org/maven2/org/openjdk/nashorn/nashorn-core/15.4/nashorn-core-15.4.jar; \
    printf '%s\n' '6f816e84dfd63a81d4eaa7829c08337bbaff3ec683ff3bf6bbd90d017a00dc6f  /var/wiremock/lib/nashorn-core-15.4.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/asm-9.10.1.jar https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar; \
    printf '%s\n' 'ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb  /var/wiremock/lib/asm-9.10.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/asm-analysis-9.10.1.jar https://repo.maven.apache.org/maven2/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar; \
    printf '%s\n' 'dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc  /var/wiremock/lib/asm-analysis-9.10.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/asm-commons-9.10.1.jar https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar; \
    printf '%s\n' '6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895  /var/wiremock/lib/asm-commons-9.10.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/asm-tree-9.10.1.jar https://repo.maven.apache.org/maven2/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar; \
    printf '%s\n' '3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b  /var/wiremock/lib/asm-tree-9.10.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/asm-util-9.10.1.jar https://repo.maven.apache.org/maven2/org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar; \
    printf '%s\n' '1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c  /var/wiremock/lib/asm-util-9.10.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/slf4j-api-2.0.20.jar https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.20/slf4j-api-2.0.20.jar; \
    printf '%s\n' '7e1446499b359675d8aabe6af2de86b85e72ae8a2a932c0b40d0e5ba57097439  /var/wiremock/lib/slf4j-api-2.0.20.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/snakeyaml-engine-3.0.1.jar https://repo.maven.apache.org/maven2/org/snakeyaml/snakeyaml-engine/3.0.1/snakeyaml-engine-3.0.1.jar; \
    printf '%s\n' '0b638c8806112215e02e58e95cbd9e9a69d4bd540991f47553d053919994cc94  /var/wiremock/lib/snakeyaml-engine-3.0.1.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/wiremock-3.13.2.jar https://repo.maven.apache.org/maven2/org/wiremock/wiremock/3.13.2/wiremock-3.13.2.jar; \
    printf '%s\n' '9946c3f0ef5f8f14d83f4c38fabd392a2ff51fe28a40bd8e674d0150e93b5399  /var/wiremock/lib/wiremock-3.13.2.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/wiremock-jetty12-3.13.2.jar https://repo.maven.apache.org/maven2/org/wiremock/wiremock-jetty12/3.13.2/wiremock-jetty12-3.13.2.jar; \
    printf '%s\n' '5fcc858f5c62106d2c8912532af3b731fa93b5aebbd7764ddc4ee50a3443e7db  /var/wiremock/lib/wiremock-jetty12-3.13.2.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/xmlunit-core-2.11.0.jar https://repo.maven.apache.org/maven2/org/xmlunit/xmlunit-core/2.11.0/xmlunit-core-2.11.0.jar; \
    printf '%s\n' '50970e6069c21182689eb44ae9f8193970e080b6f37fc13f22aa4201cca2dfe4  /var/wiremock/lib/xmlunit-core-2.11.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/xmlunit-legacy-2.11.0.jar https://repo.maven.apache.org/maven2/org/xmlunit/xmlunit-legacy/2.11.0/xmlunit-legacy-2.11.0.jar; \
    printf '%s\n' '8c924198a6dc5bcfe640bcf24607776bb4c233a1de7f07ea2562a6145dde1631  /var/wiremock/lib/xmlunit-legacy-2.11.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/xmlunit-placeholders-2.11.0.jar https://repo.maven.apache.org/maven2/org/xmlunit/xmlunit-placeholders/2.11.0/xmlunit-placeholders-2.11.0.jar; \
    printf '%s\n' 'b5fda3159f06575cc71a7c5bd3e45fb51e535aa88aef514b55103bbfe4359238  /var/wiremock/lib/xmlunit-placeholders-2.11.0.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-core-3.1.7.jar https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-core/3.1.7/jackson-core-3.1.7.jar; \
    printf '%s\n' '7608c367fd8a92666d28711519599543c180298259963184ce04d2947a8f71e7  /var/wiremock/lib/jackson-core-3.1.7.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-databind-3.1.7.jar https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-databind/3.1.7/jackson-databind-3.1.7.jar; \
    printf '%s\n' '48c781de87e2016e1f0d973748a27130f11643e7ac080173f40e2d9669d84be3  /var/wiremock/lib/jackson-databind-3.1.7.jar' | sha256sum -c -; \
    wget -q -O /var/wiremock/lib/jackson-dataformat-yaml-3.1.7.jar https://repo.maven.apache.org/maven2/tools/jackson/dataformat/jackson-dataformat-yaml/3.1.7/jackson-dataformat-yaml-3.1.7.jar; \
    printf '%s\n' 'a97e0ecd080030e4b5b55ee3b10dd330b4736951a49df4d91657f2d8678786f5  /var/wiremock/lib/jackson-dataformat-yaml-3.1.7.jar' | sha256sum -c -;
USER 1000:1000
WORKDIR /home/wiremock
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/var/wiremock/launcher:/var/wiremock/lib/*:/var/wiremock/extensions/*", "wiremock.Run"]
