# Inventario de rupturas de API de 2.0

Estado: **inventario revisado contra 144 reportes reales de 1.0.0**, 5 de noviembre de 2026.
Se registran 213 clases incompatibles, incluidas las extracciones parciales del plan 2.0. Las 161 reubicaciones que conservan el contrato
se compararon miembro por miembro con `javap -protected`, sin pérdidas de firmas tras aplicar
se documentan en cada entrada. No se ha cambiado la línea base a 2.0.

El control compara la forma completa de cada clase incompatible, incluyendo firmas, genéricos,
modificadores, excepciones y miembros públicos/protegidos. Excluye únicamente números de línea y
formato XML. Cada entrada debe documentar su motivo. Para tipos trasladados se registra además
el módulo, nombre y hash de la API de reemplazo; así una reubicación no oculta miembros perdidos.
Los cambios del inventario requieren revisión; `--observed` solo escribe hallazgos aparte.

## Observaciones de revisión 2.0 (implementación en curso)

- P1 añade la anotación neutral `pe.axiz.core.security.AuthenticatedAccess` y sus interceptores
  previos servlet/reactive. La cobertura de `@PreAuthorize` usa una gramática estructural cerrada,
  sin evaluar expresiones al arrancar. Endpoints de solo autenticación deben declararlo explícitamente;
  políticas dinámicas necesitan pruebas negativas propias. No se considera cobertura una expresión
  trivial o una exclusión de autoridad. Se conservan las reglas HTTP efectivas y los contratos
  públicos válidos como alternativas. El advice MVC conserva 401 para una denegación de método a un
  anónimo dentro de la cadena de seguridad, en lugar de convertirla en 403.

El inventario JSON inferior conserva su baseline real 1.0.0. Las nuevas huellas se revisarán con los
reportes de compatibilidad de la implementación completa; esta nota no aprueba huellas sin comprobar.


Revisión de las adiciones nativas de CQRS (1 de noviembre): `javap -protected` contra 1.0.0
comprueba que no se eliminan miembros públicos/protegidos. `AccessPolicies` añade `isEmpty()`;
los behaviors añaden constructores para `AsyncIdempotencyService` y `ReactiveAuditTrail`.
Los constructores imperativos anteriores conservan sus descriptores, pero rechazan su uso en el
pipeline reactivo: los consumidores deben migrar al overload nativo. La autoconfiguración conserva
sus firmas y declara orden posterior a auditoría e idempotencia nativas mediante `afterName`.
Se revisaron esas cuatro huellas de reemplazo; las huellas de las rupturas originales no cambian.

```json
{
  "schema": 1,
  "baseline": "1.0.0",
  "version": "2.0.0",
  "changes": [
    {
      "artifact": "axiz-adapter-audit-kafka",
      "class": "pe.axiz.core.audit.kafka.KafkaAuditPublisher",
      "sha256": "91243ed6994ed3552e8f2b69ea44571852db9a05b8e69d93ad4bc5d9c5d16421",
      "replacement": {
        "artifact": "axiz-adapter-audit-kafka",
        "class": "pe.axiz.adapter.audit.kafka.KafkaAuditPublisher",
        "sha256": "f4fd8724777033567d4ae4c2a2375d9abb31ed78f0b023be5ee185a847115c90"
      }
    },
    {
      "artifact": "axiz-adapter-audit-sqs",
      "class": "pe.axiz.core.audit.sqs.SqsAuditPublisher",
      "sha256": "6ad112c4a8c09af5520f405ab69c1c4ed094a8e11f651e646fdec17d24922930",
      "replacement": {
        "artifact": "axiz-adapter-audit-sqs",
        "class": "pe.axiz.adapter.audit.sqs.SqsAuditPublisher",
        "sha256": "cca951a0876ba4510899f73de328d0902caa0de4ec2e6a1bf487db2acebb6c71"
      }
    },
    {
      "artifact": "axiz-adapter-cache-redis",
      "class": "pe.axiz.core.cache.redis.RedisByteCache",
      "sha256": "134d33b9a2814fef643dffe8a44c2b32946f212488f2adb2c5f776338f08f847",
      "replacement": {
        "artifact": "axiz-adapter-cache-redis",
        "class": "pe.axiz.adapter.cache.redis.RedisByteCache",
        "sha256": "42e458b276bb38f70fdda26cfe2032b461725d776b03f2bb5feb33b915910a4f"
      }
    },
    {
      "artifact": "axiz-adapter-crypto-jose",
      "class": "pe.axiz.core.crypto.jose.Jose",
      "sha256": "f9eae0481e9bf41ae1623dc3466bf1c282147902a321a36496168054f8223dbf",
      "replacement": {
        "artifact": "axiz-adapter-crypto-jose",
        "class": "pe.axiz.adapter.crypto.jose.Jose",
        "sha256": "de1b43142f7fd4ce8602faf5b8095f4653f1309465c99032e1cdc50f6a3cf213"
      }
    },
    {
      "artifact": "axiz-adapter-crypto-kms",
      "class": "pe.axiz.core.crypto.kms.KmsDataKeyProvider",
      "sha256": "457fbaa75693081f90600f1592794215e97aecdcef3af8dafae4a6ace487aa07",
      "replacement": {
        "artifact": "axiz-adapter-crypto-kms",
        "class": "pe.axiz.adapter.crypto.kms.KmsDataKeyProvider",
        "sha256": "17d5e11682e151dcac1eae2605791369b57e0f6a6dd85ed93b599d70dfe78ebb"
      }
    },
    {
      "artifact": "axiz-adapter-idempotency-dynamodb",
      "class": "pe.axiz.core.idempotency.dynamodb.DynamoDbIdempotencyStore",
      "sha256": "08192c5a96b3f254950138eed6ddc3bf035839aa72d6bed064c62f52616c44a0",
      "replacement": {
        "artifact": "axiz-adapter-idempotency-dynamodb",
        "class": "pe.axiz.adapter.idempotency.dynamodb.DynamoDbIdempotencyStore",
        "sha256": "b79c7c1c53b135e2a082afd4879398f818bb347f8f0a266d09c9b352fec87c29"
      }
    },
    {
      "artifact": "axiz-adapter-idempotency-redis",
      "class": "pe.axiz.core.idempotency.redis.RedisIdempotencyStore",
      "sha256": "f5f8d85745db1b1cfd487a0d6cb371af135c070bf0b6e80edc33446bc973d13c",
      "replacement": {
        "artifact": "axiz-adapter-idempotency-redis",
        "class": "pe.axiz.adapter.idempotency.redis.RedisIdempotencyStore",
        "sha256": "05e671c2d3587a0bbf2a6b7f19a5e6f86d130dd7ea53e07280dd2e83c916bb0d"
      }
    },
    {
      "artifact": "axiz-adapter-inbox-dynamodb",
      "class": "pe.axiz.core.inbox.dynamodb.DynamoDbInboxStore",
      "sha256": "ac4c31040fe011fed74dc3aeefbb3c970839918da4d7095f7dbf3f4d7fe4d9d0",
      "replacement": {
        "artifact": "axiz-adapter-inbox-dynamodb",
        "class": "pe.axiz.adapter.inbox.dynamodb.DynamoDbInboxStore",
        "sha256": "2eaef030864913b7e13307a58d1b93db85a11c940003ebc063108c65c6f900ba"
      }
    },
    {
      "artifact": "axiz-adapter-inbox-jdbc",
      "class": "pe.axiz.core.inbox.JdbcInboxStore",
      "sha256": "d6f78d19cb3a1ac670131cf991a6863878ac5e408b10c5f391c3a0f885d1e434",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-inbox-jdbc",
        "class": "pe.axiz.adapter.inbox.jdbc.JdbcInboxStore",
        "sha256": "6b0e523f921307e1caf5a9fde24f25d9d5d1116f5eb46fc6672217513c785dff"
      }
    },
    {
      "artifact": "axiz-adapter-inbox-r2dbc",
      "class": "pe.axiz.core.inbox.r2dbc.R2dbcInboxStore",
      "sha256": "f46028bd5c798ccfbbcbfeaee0ad8fc9acd95ca1ad40daffa08f8103dfe551fe",
      "replacement": {
        "artifact": "axiz-adapter-inbox-r2dbc",
        "class": "pe.axiz.adapter.inbox.r2dbc.R2dbcInboxStore",
        "sha256": "ed4b95aba1216030eb7c2f999fa11ebfca211a09fcd9c8d4cba0effd73445e1c"
      }
    },
    {
      "artifact": "axiz-adapter-lock-dynamodb",
      "class": "pe.axiz.core.lock.dynamodb.DynamoDbDistributedLock",
      "sha256": "1c95f099af1371c9fdcd74397ec3b2acf7694bafd37add139f6e12a25d1ee88f",
      "replacement": {
        "artifact": "axiz-adapter-lock-dynamodb",
        "class": "pe.axiz.adapter.lock.dynamodb.DynamoDbDistributedLock",
        "sha256": "6f448e753d12e477623f9428196ac3641e9a9564d036f3fd9351fc4371b6c6d7"
      }
    },
    {
      "artifact": "axiz-adapter-lock-redis",
      "class": "pe.axiz.core.lock.redis.RedisDistributedLock",
      "sha256": "5d477a9ce05b4acf3381dd9e907b1fbfcd15d6b0406ae6c97e2a6e200346c736",
      "replacement": {
        "artifact": "axiz-adapter-lock-redis",
        "class": "pe.axiz.adapter.lock.redis.RedisDistributedLock",
        "sha256": "82664f10d14581bfe419dd24f1342ab4485d64b9cb30f35167821c14c0a094de"
      }
    },
    {
      "artifact": "axiz-adapter-messaging-kafka",
      "class": "pe.axiz.core.messaging.kafka.KafkaEnvelopes",
      "sha256": "ab8d11825c1d91a910d45a45c6da0bc1fc55fdccc59470482651448e0611f085",
      "replacement": {
        "artifact": "axiz-adapter-messaging-kafka",
        "class": "pe.axiz.adapter.messaging.kafka.KafkaEnvelopes",
        "sha256": "9da8fc20807013df23799ceac3db33ecfea7143e61654012009bba620e15bb40"
      }
    },
    {
      "artifact": "axiz-adapter-messaging-kafka",
      "class": "pe.axiz.core.messaging.kafka.KafkaEventPublisher",
      "sha256": "4b5503e18a69d35d09f97dec35b7af508f0f30a68272ed078b7c215971c0e44d",
      "replacement": {
        "artifact": "axiz-adapter-messaging-kafka",
        "class": "pe.axiz.adapter.messaging.kafka.KafkaEventPublisher",
        "sha256": "acfd20a5c1e6ac4370f6567efb82cd6606e4d8f2286e3e496a3cb175103bb7d4"
      }
    },
    {
      "artifact": "axiz-adapter-messaging-kafka",
      "class": "pe.axiz.core.messaging.kafka.MskIam",
      "sha256": "28f65a03b56e49782cc2475c54253a32dcff59547d90c2b10e57e134513a5abc",
      "replacement": {
        "artifact": "axiz-adapter-messaging-kafka",
        "class": "pe.axiz.adapter.messaging.kafka.MskIam",
        "sha256": "89baf953fb19adab263ff175659692f0fd248affec9030a9ad3b80c048593d35"
      }
    },
    {
      "artifact": "axiz-adapter-messaging-sns",
      "class": "pe.axiz.core.messaging.sns.SnsEventPublisher",
      "sha256": "3830308eec8eea615ba81bdd84d549a4b61ced248d0821032abd8912665f3c9c",
      "replacement": {
        "artifact": "axiz-adapter-messaging-sns",
        "class": "pe.axiz.adapter.messaging.sns.SnsEventPublisher",
        "sha256": "3b3c8cae55900fff509855f0a97b3e217216b6bbdf7cdc2eaacd1bba8f631f1d"
      }
    },
    {
      "artifact": "axiz-adapter-messaging-sqs",
      "class": "pe.axiz.core.messaging.sqs.SqsEnvelopes",
      "sha256": "1e3185dd2046de91098d5ec8587401282849292eeb6c91d3fe0fb0efcd7ccd26",
      "replacement": {
        "artifact": "axiz-adapter-messaging-sqs",
        "class": "pe.axiz.adapter.messaging.sqs.SqsEnvelopes",
        "sha256": "f08e0841e17b0e9f7a66ba047a673e059cf245ed527c61a138c76520d728ef3e"
      }
    },
    {
      "artifact": "axiz-adapter-messaging-sqs",
      "class": "pe.axiz.core.messaging.sqs.SqsEventPublisher",
      "sha256": "462f093dc078147899b0e3e6e1dd1f9948bf1b6eb597c5754b84e15affe33343",
      "replacement": {
        "artifact": "axiz-adapter-messaging-sqs",
        "class": "pe.axiz.adapter.messaging.sqs.SqsEventPublisher",
        "sha256": "af4b7c9cba8ead10580f3bb118ac3f43e4d5eb7325e339d545ef03fd338f5be6"
      }
    },
    {
      "artifact": "axiz-adapter-observability-aws",
      "class": "pe.axiz.core.observability.awssdk.AwsClients",
      "sha256": "a0b86321477ec3d00a6d7e6685492d5f9ad141c8d95c268d4b16c4d21ec06cc8",
      "replacement": {
        "artifact": "axiz-adapter-observability-aws",
        "class": "pe.axiz.adapter.observability.aws.AwsClients",
        "sha256": "4296cb45f212e201b7284e6e8c06a54b326de642df1839eea586e692930f93fc"
      }
    },
    {
      "artifact": "axiz-adapter-observability-aws",
      "class": "pe.axiz.core.observability.awssdk.ObservationExecutionInterceptor",
      "sha256": "c363e7776325435d9b0ffc31753da8b96de31f27b5007798070a991ca56c637b",
      "replacement": {
        "artifact": "axiz-adapter-observability-aws",
        "class": "pe.axiz.adapter.observability.aws.ObservationExecutionInterceptor",
        "sha256": "3700a147913e9db18e1d9378f7de413dbd736fea0f677d143ab468e4be7ba429"
      }
    },
    {
      "artifact": "axiz-adapter-outbox-jdbc",
      "class": "pe.axiz.core.outbox.Dialect",
      "sha256": "4c26ddf9d40aafe249b5585302d6bfd6da148d2ed15909cfaa01d2f04184f4bf",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-outbox-jdbc",
        "class": "pe.axiz.adapter.outbox.jdbc.Dialect",
        "sha256": "b70190cbdefe8d74de7cba7489c32f00af3aec9ea18b277c51c52cd93398aded"
      }
    },
    {
      "artifact": "axiz-adapter-outbox-jdbc",
      "class": "pe.axiz.core.outbox.JdbcOutboxStore",
      "sha256": "0cc7c79fb40865598ab90a20b3e491d8ad11fef2f4e49ce683e7e6d6c4cb64c2",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-outbox-jdbc",
        "class": "pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore",
        "sha256": "899096b6469260f54982ae591b31effcf89344a2e89eb22683db5976ea56348f"
      }
    },
    {
      "artifact": "axiz-adapter-outbox-r2dbc",
      "class": "pe.axiz.core.outbox.r2dbc.R2dbcOutboxStore",
      "sha256": "0f9bab0925a0de4a0202a071ccf2d53c6ef518de6607e6e38406c64c727b8b63",
      "replacement": {
        "artifact": "axiz-adapter-outbox-r2dbc",
        "class": "pe.axiz.adapter.outbox.r2dbc.R2dbcOutboxStore",
        "sha256": "20c8db62ec6a8dbf1a79c4f354fd3f826a5b0c2e4e929bf9d0909f40231d24cf"
      }
    },
    {
      "artifact": "axiz-adapter-outbox-r2dbc",
      "class": "pe.axiz.core.outbox.r2dbc.ReactiveOutboxWriter",
      "sha256": "194088b7d17fe9c1376f0cc60257b47e41adacb7e761b6700ffd0af38ac40ec7",
      "replacement": {
        "artifact": "axiz-adapter-outbox-r2dbc",
        "class": "pe.axiz.adapter.outbox.r2dbc.ReactiveOutboxWriter",
        "sha256": "291361241a1e2fdc176253591233a8e69efa7b0013a1897503973ca981309dd0"
      }
    },
    {
      "artifact": "axiz-adapter-saga-jdbc",
      "class": "pe.axiz.core.saga.JdbcSagaStateStore",
      "sha256": "df3e68033f45eb16c84dc5f92d9a57ce1459b1deeda11ee481051b2e7d66fe11",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-saga-jdbc",
        "class": "pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore",
        "sha256": "699819eb84f9512d4a939f288ea6b9118afb1e12453d6f30f6a8d6185870c412"
      }
    },
    {
      "artifact": "axiz-adapter-secrets-aws",
      "class": "pe.axiz.core.secrets.aws.ParameterStoreResolver",
      "sha256": "7ed29a29035b4c4d707ab5e24bcee114f68dd171dc3fc822ed3e0ac2f074f043",
      "replacement": {
        "artifact": "axiz-adapter-secrets-aws",
        "class": "pe.axiz.adapter.secrets.aws.ParameterStoreResolver",
        "sha256": "143839883abbf7dbb35ab31635d262284231cb6d78f9e440fcbb719a9b9d50d6"
      }
    },
    {
      "artifact": "axiz-adapter-secrets-aws",
      "class": "pe.axiz.core.secrets.aws.SecretsManagerResolver",
      "sha256": "1dd5b536b81e8bb14f47480e4050baf3acfbfa03dac7eadec4b45b303b1b13eb",
      "replacement": {
        "artifact": "axiz-adapter-secrets-aws",
        "class": "pe.axiz.adapter.secrets.aws.SecretsManagerResolver",
        "sha256": "8a7b19635ca85794b5f99b4d2d63fb0956aa6657d4f32d8d733bf74a5da15496"
      }
    },
    {
      "artifact": "axiz-adapter-security-redis",
      "class": "pe.axiz.core.security.redis.RedisNonceStore",
      "sha256": "8dba1f216d71df8ea72651d207aaa2aff2767f440bbf7753548ef1d46435b461",
      "replacement": {
        "artifact": "axiz-adapter-security-redis",
        "class": "pe.axiz.adapter.security.redis.RedisNonceStore",
        "sha256": "0ccca4053e4a3fe499968110a2edb73aa7769fa3df83240911e53dfd3340ae60"
      }
    },
    {
      "artifact": "axiz-adapter-security-redis",
      "class": "pe.axiz.core.security.redis.RedisRateLimiter",
      "sha256": "975314622d59983e3a5fc50fd438178980d813f72ecffc2a25bba0e3d5816d1e",
      "replacement": {
        "artifact": "axiz-adapter-security-redis",
        "class": "pe.axiz.adapter.security.redis.RedisRateLimiter",
        "sha256": "114cd1dbebf317912a9940981f826cae94794ee694e52f41707d7d9157ac8c00"
      }
    },
    {
      "artifact": "axiz-adapter-sequence-dynamodb",
      "class": "pe.axiz.core.sequence.dynamodb.DynamoDbSequenceStore",
      "sha256": "6a193ec5e301b3f3d5bf16c42a96d668afe5f809abb4980bdfc8ff37333d3f59",
      "replacement": {
        "artifact": "axiz-adapter-sequence-dynamodb",
        "class": "pe.axiz.adapter.sequence.dynamodb.DynamoDbSequenceStore",
        "sha256": "11a38ec45748a674d2763a26944805c4ba81288379ab6a566d6f8d141912a5fb"
      }
    },
    {
      "artifact": "axiz-adapter-sequence-jdbc",
      "class": "pe.axiz.core.sequence.JdbcSequenceStore",
      "sha256": "6f659c2a4a41f8d7ad6ecb7575f05bbd383a4a4a8ed7cb774078cc6e429ce8e5",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-sequence-jdbc",
        "class": "pe.axiz.adapter.sequence.jdbc.JdbcSequenceStore",
        "sha256": "80abcd0db9cb30e583692ddb1ccadb4ff864d9011bc61d11eecfee4edab2d916"
      }
    },
    {
      "artifact": "axiz-adapter-storage-s3",
      "class": "pe.axiz.core.storage.s3.S3ObjectStorage",
      "sha256": "ce0f6e998d6a5fd70a40b05340d64932ab88d16b7d909786d571a0f0380dbb12",
      "replacement": {
        "artifact": "axiz-adapter-storage-s3",
        "class": "pe.axiz.adapter.storage.s3.S3ObjectStorage",
        "sha256": "be0cffb13b57fd8f149f091ed452c31c77e63cfddef38c7d26564c6da62987a4"
      }
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.ArtifactGenerator",
      "sha256": "74dd0a4667cb9362c2e6a60e50b51238b31694410d06a7d0dda1ba4916ba4c2c",
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.model.Archetype",
      "sha256": "b3d97538be87be5d61b774e4492536b37f027988992322edfc9de5175921de4f",
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.model.Capability",
      "sha256": "a362c02349bab84b632d668eb8e8bde0688af8d9951e6bd574ea9c9f1c91cab7",
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.model.Catalog",
      "sha256": "7155e6ae1e4c9814abbc66340c2168b997e9b89217b2f8f8112ef288ad0f61d5",
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.model.Framework",
      "sha256": "aef6a8dc78e726a9ed3d98f3de521e1d9f3a4697720ed4f5abeba2ed6dc32e9a",
      "replacement": {
        "artifact": "axiz-codegen",
        "class": "pe.axiz.codegen.model.Structure",
        "sha256": "8b92c798288438a7023ad762fc2fafe19297d5b2587a629f6ee3c08d755d3ee6"
      }
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.model.ProjectRequest",
      "sha256": "7f6d171dec91a6ef293dedfcd2579aa3edd019882ade9028ee829ed2cc5ff7ab",
    },
    {
      "artifact": "axiz-codegen",
      "class": "pe.axiz.codegen.project.ProjectManifest",
      "sha256": "5e82e1e949c2a4fde5c45f6a3408dd412bea799eab1d18c3619d9dbd0d8348fc",
    },
    {
      "artifact": "axiz-core-commons",
      "class": "pe.axiz.core.commons.jdbc.ConnectionProvider",
      "sha256": "3b078132894a223e8182ab6f7057255e9411e3cccf2196315c5ef9db5942ee12",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-kit-jdbc",
        "class": "pe.axiz.kit.jdbc.ConnectionProvider",
        "sha256": "41ec9a17bcdb23fbea75a4adcb1e24bc4664db7f67cff6459b8378fb5c9f3ee4"
      }
    },
    {
      "artifact": "axiz-core-commons",
      "class": "pe.axiz.core.commons.jdbc.JdbcTransactions",
      "sha256": "721327a3f385e17f016cf7fd154bf946e3d3d14d713bab3a5200c682db81298b",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-kit-jdbc",
        "class": "pe.axiz.kit.jdbc.JdbcTransactions",
        "sha256": "47bfd09ce531aab7fdbee9f4d852fdb14d075ea266664b04647eabb0e9ad5beb"
      }
    },
    {
      "artifact": "axiz-core-commons",
      "class": "pe.axiz.core.commons.jdbc.SqlStates",
      "sha256": "937feb5011c4d341f8f8ca30ab6b122f513ff660a38061442111efbe86f03e1b",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-kit-jdbc",
        "class": "pe.axiz.kit.jdbc.SqlStates",
        "sha256": "84674eb56c0e901f567eb6ba9306ddf8f2ca4aebab7cb3316e3d09d19b7942ba"
      }
    },
    {
      "artifact": "axiz-core-commons",
      "class": "pe.axiz.core.commons.jdbc.TransactionRunner",
      "sha256": "dd80fafc151b05ab000a8a1a50f6db2fccbd686302dd1010750ec1b21e086e3a",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-core-commons",
        "class": "pe.axiz.core.commons.tx.TransactionRunner",
        "sha256": "a6f794103c5648ca7ac543279f5d0b4a01c29e8adf6d1f7a680d2aac1d0390bd"
      }
    },
    {
      "artifact": "axiz-core-idempotency",
      "class": "pe.axiz.core.idempotency.IdempotencyStore",
      "sha256": "bcf5b38f66d5155910d9224b85534aa9ccf477c1e0fae068d2997a8771c4e74a",
      "reason": "A2/A3: compareAndSet(expected,replacement) es obligatorio para cercar dueño, generación y versión; un almacén propio debe implementar CAS atómico. renew/markUnknown/resolve se ofrecen como defaults que usan ese CAS. Contratos concurrentes en memoria, Valkey y DynamoDB."
    },
    {
      "artifact": "axiz-core-inbox",
      "class": "pe.axiz.core.inbox.JdbcInboxStore",
      "sha256": "8f6b73abcce228befb1b14a7a2f301525f380fa1ea618fef7cadd522071c00ac",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-inbox-jdbc",
        "class": "pe.axiz.adapter.inbox.jdbc.JdbcInboxStore",
        "sha256": "6b0e523f921307e1caf5a9fde24f25d9d5d1116f5eb46fc6672217513c785dff"
      }
    },
    {
      "artifact": "axiz-core-outbox",
      "class": "pe.axiz.core.outbox.Dialect",
      "sha256": "4c26ddf9d40aafe249b5585302d6bfd6da148d2ed15909cfaa01d2f04184f4bf",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-outbox-jdbc",
        "class": "pe.axiz.adapter.outbox.jdbc.Dialect",
        "sha256": "b70190cbdefe8d74de7cba7489c32f00af3aec9ea18b277c51c52cd93398aded"
      }
    },
    {
      "artifact": "axiz-core-outbox",
      "class": "pe.axiz.core.outbox.JdbcOutboxStore",
      "sha256": "783d5cd48c3accc1d382b16cdbd93c9171007b6858b79954faa8457c26a15bc3",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-outbox-jdbc",
        "class": "pe.axiz.adapter.outbox.jdbc.JdbcOutboxStore",
        "sha256": "899096b6469260f54982ae591b31effcf89344a2e89eb22683db5976ea56348f"
      }
    },
    {
      "artifact": "axiz-core-outbox",
      "class": "pe.axiz.core.outbox.OutboxRelay",
      "sha256": "9b5efd69b4df035835088216c1fcafbaa4c6b5eaaeaf8302d8a44329a94ede0d",
      "reason": "M8/M9 y A1: el constructor recibe TransactionRunner de core.commons.tx; añade overload con lease. La receta actualiza el tipo del puerto; claim y marca son transacciones breves y la publicación ocurre fuera de SQL."
    },
    {
      "artifact": "axiz-core-outbox",
      "class": "pe.axiz.core.outbox.OutboxStore",
      "sha256": "74f2fc216f08ef20dfe04ad43e021c3caca926bfb32be4cf24c80792091f1f63",
      "reason": "A1/M1: nuevos métodos obligatorios claimDue, marcas cercadas por owner, retry/skip explícitos, verifySchema y contadores persistidos. Almacenes propios deben implementar el protocolo; no se suministra un default inseguro que pierda fencing u orden."
    },
    {
      "artifact": "axiz-core-saga",
      "class": "pe.axiz.core.saga.JdbcSagaStateStore",
      "sha256": "ec5383a9a6915f5d7b56244acdabe3ff2f2189f87e59138798303ce9d3b7b5a6",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-saga-jdbc",
        "class": "pe.axiz.adapter.saga.jdbc.JdbcSagaStateStore",
        "sha256": "699819eb84f9512d4a939f288ea6b9118afb1e12453d6f30f6a8d6185870c412"
      }
    },
    {
      "artifact": "axiz-core-sequence",
      "class": "pe.axiz.core.sequence.JdbcSequenceStore",
      "sha256": "c7791adbfb6bf8f5f2398f520aee15fe4df08f053ecc169e82f577589f6e964b",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-adapter-sequence-jdbc",
        "class": "pe.axiz.adapter.sequence.jdbc.JdbcSequenceStore",
        "sha256": "80abcd0db9cb30e583692ddb1ccadb4ff864d9011bc61d11eecfee4edab2d916"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.AccessPolicies",
      "sha256": "173712f13fb88b7922d96d3b8182254cd78460ab5f715614ac8830d75a85d2a8",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.AccessPolicies",
        "sha256": "30509251e2836c499a832fb623d666440827199b11b9d2a08ab7896f041d5631"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.AccessPolicy",
      "sha256": "9a211dc12c57f14474dfc4e4f2d335854be6bcaf3e438262309921569cd3e162",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.AccessPolicy",
        "sha256": "dff34710077dfe816434a8dc934785fdfdbaa87b6bcebfe01d624ef026979edd"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.AuditedCommand",
      "sha256": "44c5b81fc75c7ce1c1a993d15df6b5c827c3d93ec1ec2bec3c292a36948ff6f4",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.AuditedCommand",
        "sha256": "d46c94746549177d99b5d5e22af00766446879bd8bcba469101984fe3c9c66b6"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Buses",
      "sha256": "2c3406a492f3466a9983c90627d27b3861e5cda466e6ce019eafc9eb7828161c",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Buses",
        "sha256": "4878d717862dfdce8b1ef1e17217a09232e7d90e1259fe417c14f9f4504152d9"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Buses$Registration",
      "sha256": "9e7413d8663bca8edac3c72a410cef16a484386a8afb0800bc39550e9c90e884",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Buses$Registration",
        "sha256": "07c6288f38e259b38bc3f9bc48125d89ddf3a6092a7371a9b99d14003bc7321f"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Caller",
      "sha256": "968d0fcce970ba873a79656da98b767970f03f751993b30a4561e37f954ea60b",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Caller",
        "sha256": "1428107479ebf07477ab5962ea2329e377fb119a92a2fdd226b128be604c27be"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Command",
      "sha256": "a52793c7b79ab44645555715842dd59c32ac275f9fabd5b455174c646063fa50",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Command",
        "sha256": "41bd9c237908384f1d9c2ee1ff90318d129ea410c7e5fb5883aa6f266e165981"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.CommandBus",
      "sha256": "977aa2ed15fb6526a2407aa48efae04aec5bb3784621459bed51fa120c0ddda3",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.CommandBus",
        "sha256": "6fcf86e79b4e2b9d54d0e13a5487079b65ae70bacb6506c1e327583a8379adcb"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.CommandHandler",
      "sha256": "d1ace3af43b1a7e1072f0efe66e0c099d0c28d46a7a7f74ff5aa552f40859b6f",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.CommandHandler",
        "sha256": "83a8ab2fd9ee843fe628864d93918ddd663094bb939774450192b44da070e6a1"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.HandlerNotFoundException",
      "sha256": "277eea7d8601382527bb2c26ecc1a9610c6940bb8bd5731fa1883c2d360e6035",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.HandlerNotFoundException",
        "sha256": "68285c40d522c124116f28b1289192f5f920f80e1f46fc224e7a7f7da01691d8"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.IdempotentCommand",
      "sha256": "cf82d44a6ce6ca73142993879aa537d97c064176bcc1669342447cf71f3fdadf",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.IdempotentCommand",
        "sha256": "ef9bb1002a1fcf9b969a3d587c26db53e1510db13fe49e61c7452701c2972e78"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Invocation",
      "sha256": "3858dccd5d8b02cb3c30b9c1c3488b0f84fcc72f60850c784e2923e89a576c1a",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Invocation",
        "sha256": "4847a7e464e48d18555272f21f04b22e3b955d3845de589fc40ac07b4293c4a7"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.OpenAccess",
      "sha256": "facba2bc1786f4ad9d091e44c2dbd5cc4c749555051b4e023fc99b9c74f9860c",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.OpenAccess",
        "sha256": "14e5b1384819ba7517cb7069f7301750806de1ba965d568e82d4d483a0169daa"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.PipelineBehavior",
      "sha256": "6f1c1436789e2fca9830554b7ac5d06c30fb2944680d2c697aa81b99bf903571",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.PipelineBehavior",
        "sha256": "85c6ccaa21d60b2da2a1aa3cb4ac2404cb8707f15ef345b9d46d7cc24eae2730"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.PipelineBehavior$Next",
      "sha256": "6a6ce245115f1ce4903335ccd0de444446e9c7799dd8af602977ed589694c339",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.PipelineBehavior$Next",
        "sha256": "19818e41d230bb2e9c9ad592123c4c8e135f0fbe4f0e9c00c10f6d75e0ced27a"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Projection",
      "sha256": "93c492236b71cffd7f7a3cedd907b1a3822f837f789b2b75587875904c2ccf3b",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Projection",
        "sha256": "42d48bdb5b160ff30c2c4af1a4f8908754e5597c104706e4da5d459b6688fb05"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Query",
      "sha256": "f7c9ae1d040f3a8ee2e9eb331b779646af2c7d7dd4458085f2b0480cdcfbb0ae",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Query",
        "sha256": "e535d87fb16aaab599aff9253cd086991842cde3a4160a7febc3e28f5f89ba70"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.QueryBus",
      "sha256": "45bb8ee3be99770005ed2b1a318184b746889087a567bb3301bf080a574d73bb",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.QueryBus",
        "sha256": "bcb780cfa563ac8864f05b5fa440f47f8896b1fcf330552c70498af99ad72a49"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.QueryHandler",
      "sha256": "710ab73be32bdf1a4294424a6321ae406f08d48767013751a24975b26ba4c57a",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.QueryHandler",
        "sha256": "2c36d20ed91bac90acf3e325f4e4c77cc9e2d6484e37e797b4f95c555bafbb18"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.ReactiveAccessPolicy",
      "sha256": "c0f54d3d222721e52243d5df57011979338d06aa0f49f9fce26c4ffa749adfa3",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.ReactiveAccessPolicy",
        "sha256": "4d0a0eb1fbc344291f4d97e334ebad9ff1c0419854ad03c5f7cc2369675404dd"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.ReactiveCommandHandler",
      "sha256": "07dc11e2740dc5f58dcdc62e53d202b0878b481698cf18514f52a63a70198e74",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.ReactiveCommandHandler",
        "sha256": "8587743afeeef15b25eb3d3684b4331cd1c123527f555398bb0245a5cbacfa28"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.ReactiveQueryHandler",
      "sha256": "02dc812e82895360790b64c1c2c3c9c938db5e2720a042b0e3393c423ff98ef9",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.ReactiveQueryHandler",
        "sha256": "6b4bc01ef724cc9baf4d071851ae76c01cfd7c303f5e04dd6341f0384e11151a"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.Request",
      "sha256": "6b0ac3e589b6658de1fc42ae8e896dd884490502ee4f2d979f5808035057b90d",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.Request",
        "sha256": "3119031a26a123c9e892a6149dd1f8bf2e520de92286d6cbac824818ebf52d83"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-api",
      "class": "pe.axiz.framework.cqrs.RequiresAuthority",
      "sha256": "7739be2d583715c5322e2de3e3fe5c3a2b5792e17023bd921dfc885b480d0d84",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.RequiresAuthority",
        "sha256": "a749b4330e8266f0e3ec1b4d2eb88f0c0012b6468802df72f158d58c9a067a87"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.AuditBehavior",
      "sha256": "cc7edecb53609f10779375b1a7884ccefcca2e6720fb27e12c5765c0b941d235",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.AuditBehavior",
        "sha256": "127c33745570192c7ed4f15d9e809303a416bf1803066290fbd77c8359bb244e"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.AuthorizationBehavior",
      "sha256": "94d39c014e0dcdee7dbf8f25d40f63dd24f78dd2bbab6cb424d2554826537098",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.AuthorizationBehavior",
        "sha256": "d3b0d2b6f67239aab38b67cea37ee1129c24fe69d4d26f6dd67765634285df3e"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.AxizCqrsAutoConfiguration",
      "sha256": "5c9fd08f1f6de0bd15aa1692046826d870fe602db022dbe3b04430217f7a7c56",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.AxizCqrsAutoConfiguration",
        "sha256": "2e78bd8ed55cb13a27900e2e5e50bf378dd88ad8bc45a287f525feb34e7193f2"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.AxizReactiveCqrsAutoConfiguration",
      "sha256": "9a6b70a839b12e69a493be95e1a9c78579c85ea25a1b20f733cc9ffa504a0ac4",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.AxizReactiveCqrsAutoConfiguration",
        "sha256": "9cd4f5d3f18a421d38a3b70fc8615f6d35e5166de2db991517f280e0ad6ae7b2"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.IdempotencyBehavior",
      "sha256": "5a58b3989a6491a349985693a21fa367c6dc96c6cc020ac55a41e42407564e72",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.IdempotencyBehavior",
        "sha256": "11ccbc4bf9f197be67777d6d14af2950cba234f23bce6b89e22ccf55f643641e"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.MetricsBehavior",
      "sha256": "61b6dba55d8431ef686e404db7faa5d65f5861a8e99fe74294cbdecca327474a",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.MetricsBehavior",
        "sha256": "d99910d194649c5b74bf7d0f498e5c55ff21d71c211998dbe1c471f1898150de"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ObservationBehavior",
      "sha256": "24a4276f250e5a3d19b810f7814fd278c9e7f8c391005585d3f813caf2b3a4ac",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ObservationBehavior",
        "sha256": "88101822b0ac852f0bc8a2813019f38debca92f7e1a24cea0b79b73ef8bddb38"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveAccessPolicy",
      "sha256": "2823f95359c1eef0764ea80426bd5c96486fe165d343edf031f24b4cd97107ae",
      "reason": "B6: elimina el puente spring y dirige a ReactiveAccessPolicy del módulo API, cuyo check retorna Publisher<Void>. Mono<Void> sigue siendo covariante; la receta migra ambos paquetes antiguos y la prueba compila el resultado contra la API 2.0 real.",
      "replacement": {
        "artifact": "axiz-extension-cqrs-api",
        "class": "pe.axiz.extension.cqrs.ReactiveAccessPolicy",
        "sha256": "4d0a0eb1fbc344291f4d97e334ebad9ff1c0419854ad03c5f7cc2369675404dd"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors",
      "sha256": "9127fed70e57df15e125c7194c64b22982f8901eb57792a2a2c5730b681232e7",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors",
        "sha256": "c90d7760b0b7578affbfc3b22b8f1f19950688551ef2a54ce0e19ed85957aa58"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Audit",
      "sha256": "af98651f2482c25b721f0e43aeed75cb9bd5f5be1641c515dbd99867b7fc74d3",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Audit",
        "sha256": "1a12e7663ca1ced6b41553748bc1bdb20e6f51e8dc8ff844b5e00491f0aba1e1"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Authorization",
      "sha256": "7eb8ff06705d59bab7409099bff4b8a6178a6495889640f5eb522ce4d938e5db",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Authorization",
        "sha256": "bf64d72a90bb748fc26579c26cf5ba7557139f65519423d36710e34ead73f7c1"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Idempotency",
      "sha256": "8f384d8911b6aca7fee2a50d5154cd0ee79aec8a5f4a6d50408bd32d6db1a3c9",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Idempotency",
        "sha256": "9bee87c3d91d5bf2a93020ffaa95b2e97f45359a66ce7665718f6f4d6f42557b"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Metrics",
      "sha256": "5f82a30c9ffaed43850063b3c5e557650b8cc4dd62ce57a45eafb2c8f2b89b43",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Metrics",
        "sha256": "f9ed9e5ae1d87bde2d723897a28818a9b9e2594edcc9b41df9f29c8fa5e19367"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Observation",
      "sha256": "25d960e06de563d36163b69a2cd5cd47a9fd28db18f308f19c5c06d5d7a37ad5",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Observation",
        "sha256": "7b10bdc08e4a1e6126b37daba5171588abf5d52d286daa60b760b0a1e04b9d63"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Transaction",
      "sha256": "56772f9f7a1e33dac3f79527aab8e5a6550e0f284f060585f773c4958199cc29",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Transaction",
        "sha256": "f8bf84a9ed12473c30bd463478fdcbb8b4fe5df31b1f3a2a369558928b6f8c68"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBehaviors$Validation",
      "sha256": "030f5b52ae3068e08ef0fff734ca326955d0be12a3171427b4d195c69f1e6840",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBehaviors$Validation",
        "sha256": "ce70a554788a10e131f480765647f3159ef8737399b4047ac80d46b0670650bb"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBuses",
      "sha256": "590894eff5c5b096fc4e8cd575013c1097f74e60b0ce8e979c8d727db56844d4",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBuses",
        "sha256": "6085e12e135d7ba99faab2af46a2e265be35646d26db3e37ce8c97dbd18cc45c"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveBuses$Registration",
      "sha256": "266432911c948405b1baf194e72cc1dc3b7956f98649af7dfc80f938321d2eba",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveBuses$Registration",
        "sha256": "a62703001d7c5b8d9387df61805f7d659f34013a4fac22b2331da8536ebf095d"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveCommandBus",
      "sha256": "fce0a117d932d8339e32b1c06ee780d2fc804fcdb948a3dd46127cbe8ff22578",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveCommandBus",
        "sha256": "c069c212e2dd64222992a289fe84ed8aecf0d33d1386e19fcb28c0c5e1848f74"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactivePipelineBehavior",
      "sha256": "d764459396fb22ad4ddd90757d5c7801b6484fe6a1496e2976e87265ee4dbdaa",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactivePipelineBehavior",
        "sha256": "78f1e82a8daf7e21f2d35cbd4499f041153ffeaae3092ef86951948898fbda14"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactivePipelineBehavior$Next",
      "sha256": "686c62d3f3681caa67c83f213f9cc69bfcae5949a57d5f68d1cd8e4a7db370e0",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactivePipelineBehavior$Next",
        "sha256": "044400eb9b643a9a213498158d3a9d6a3d8149f66175805d0ddbbce8e6f9fc2d"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ReactiveQueryBus",
      "sha256": "b7d76cea38d8a672751ff48c6c432fc4d72780a5b892549015ab9b91aa1c88e0",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ReactiveQueryBus",
        "sha256": "fff4b336f994e755e7ef0b54b35d3920d2e90ab207201d6093c02c79bc35e66c"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.TransactionBehavior",
      "sha256": "aaecaf68a508497a6eaed3f2c497c41a4f87dc6f7522df2a03b6a7a3594b1aec",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.TransactionBehavior",
        "sha256": "14488339601a10333777c704f839495ea654d7f56b8b1c5499cf21a49e6302d8"
      }
    },
    {
      "artifact": "axiz-extension-cqrs-spring-boot-starter",
      "class": "pe.axiz.framework.cqrs.spring.ValidationBehavior",
      "sha256": "5a8a87a9d99a9d142f6aab252b7f63cfb458151c80b4e96b682628c727de3ffb",
      "replacement": {
        "artifact": "axiz-extension-cqrs-spring-boot-starter",
        "class": "pe.axiz.extension.cqrs.spring.ValidationBehavior",
        "sha256": "76fe7fd7009b22c6da54c4e974a4f1a105e4728284844e79394c8ea17b434e84"
      }
    },
    {
      "artifact": "axiz-kit-dynamodb",
      "class": "pe.axiz.core.persistence.dynamodb.CursorCodec",
      "sha256": "0cea253219e76f0b68978f5c7451a73bdc0a863eadd3eb37bfb398be4ed073d0",
      "replacement": {
        "artifact": "axiz-kit-dynamodb",
        "class": "pe.axiz.kit.dynamodb.CursorCodec",
        "sha256": "cc77122a7a52ecad505e076861af761001a7f94e5e8a640fe09994f4bdd92549"
      }
    },
    {
      "artifact": "axiz-kit-dynamodb",
      "class": "pe.axiz.core.persistence.dynamodb.DynamoRepository",
      "sha256": "50306a6d22195d55f2ac2acda5abe493aad72aef7b58c39f5c48504eb4209a49",
      "replacement": {
        "artifact": "axiz-kit-dynamodb",
        "class": "pe.axiz.kit.dynamodb.DynamoRepository",
        "sha256": "cf617c4492c0625a16d882323d432cf00e9c70e134df7321b2113357e9144e50"
      }
    },
    {
      "artifact": "axiz-kit-dynamodb",
      "class": "pe.axiz.core.persistence.dynamodb.EncryptedAttributeConverter",
      "sha256": "ddb0059401b21ca8701c532372c8eeb0a64fba7166e641c9c14727f2c6f1e432",
      "replacement": {
        "artifact": "axiz-kit-dynamodb",
        "class": "pe.axiz.kit.dynamodb.EncryptedAttributeConverter",
        "sha256": "b97c101d2958af573a6a7b4a92e389581c320ecefd729cae3173187a91206424"
      }
    },
    {
      "artifact": "axiz-kit-dynamodb",
      "class": "pe.axiz.core.persistence.dynamodb.MoneyAttributeConverter",
      "sha256": "7e3871720563a3c201d8c489787d240613ce633d800e2fc1973680a32b6200a7",
      "replacement": {
        "artifact": "axiz-kit-dynamodb",
        "class": "pe.axiz.kit.dynamodb.MoneyAttributeConverter",
        "sha256": "61bc191b70206835329d6745c9d0ad0313e2f60ae1ebaafcd69824162e8162aa"
      }
    },
    {
      "artifact": "axiz-kit-jdbc",
      "class": "pe.axiz.core.commons.jdbc.ConnectionProvider",
      "sha256": "3b078132894a223e8182ab6f7057255e9411e3cccf2196315c5ef9db5942ee12",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-kit-jdbc",
        "class": "pe.axiz.kit.jdbc.ConnectionProvider",
        "sha256": "41ec9a17bcdb23fbea75a4adcb1e24bc4664db7f67cff6459b8378fb5c9f3ee4"
      }
    },
    {
      "artifact": "axiz-kit-jdbc",
      "class": "pe.axiz.core.commons.jdbc.JdbcTransactions",
      "sha256": "4b0c144d02d44ffc51741716fb309080e31f0ec0a88e7c65b455a152769d6889",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-kit-jdbc",
        "class": "pe.axiz.kit.jdbc.JdbcTransactions",
        "sha256": "47bfd09ce531aab7fdbee9f4d852fdb14d075ea266664b04647eabb0e9ad5beb"
      }
    },
    {
      "artifact": "axiz-kit-jdbc",
      "class": "pe.axiz.core.commons.jdbc.SqlStates",
      "sha256": "937feb5011c4d341f8f8ca30ab6b122f513ff660a38061442111efbe86f03e1b",
      "reason": "M8/M9: extracción parcial JDBC aprobada; el artefacto core conserva puertos puros. Comparación con el predecesor real 1.0.0 tanto en core como en el nuevo módulo. La receta cambia tipos individuales; el catálogo/upgrade añade el adaptador runtime. JdbcTransactions preserva el error principal de rollback/restore y SqlStates limita duplicados a 23505/23000.",
      "replacement": {
        "artifact": "axiz-kit-jdbc",
        "class": "pe.axiz.kit.jdbc.SqlStates",
        "sha256": "84674eb56c0e901f567eb6ba9306ddf8f2ca4aebab7cb3316e3d09d19b7942ba"
      }
    },
    {
      "artifact": "axiz-kit-jpa",
      "class": "pe.axiz.core.persistence.jpa.AuditableEntity",
      "sha256": "fa8838788f17a642cf0e3b663c31ea39110b8911b418e282f177c7efc00ce5ba",
      "replacement": {
        "artifact": "axiz-kit-jpa",
        "class": "pe.axiz.kit.jpa.AuditableEntity",
        "sha256": "8cabfb6f77ded00044f47ddcc4aafca0c787d4cd34ab94adc74667abf6daef37"
      }
    },
    {
      "artifact": "axiz-kit-jpa",
      "class": "pe.axiz.core.persistence.jpa.AuditingRegistry",
      "sha256": "c07cd62dec1cef74ff8a355954c812875d0490e4ecc67bacb6a88d9e634167cc",
      "replacement": {
        "artifact": "axiz-kit-jpa",
        "class": "pe.axiz.kit.jpa.AuditingRegistry",
        "sha256": "2b21c359a78fbfa9b03a5f9b6bbc5b03c83eb6fb91d790649ca609f39b9353c0"
      }
    },
    {
      "artifact": "axiz-kit-jpa",
      "class": "pe.axiz.core.persistence.jpa.AuditListener",
      "sha256": "4575a19e78d2fca2c6ac954f241744c0aa7f34d7483cd1c47c7ad3d0d91454ee",
      "replacement": {
        "artifact": "axiz-kit-jpa",
        "class": "pe.axiz.kit.jpa.AuditListener",
        "sha256": "a4187604608c90a97cd1e2fea191e8dee0a50032380572a4d73a5fbcde5e33d1"
      }
    },
    {
      "artifact": "axiz-kit-jpa",
      "class": "pe.axiz.core.persistence.jpa.AuditorProvider",
      "sha256": "5afc69eded99a728818d2e23f5c58e5fe9a314e9a4f063350e2b0e477c54c0e6",
      "replacement": {
        "artifact": "axiz-kit-jpa",
        "class": "pe.axiz.kit.jpa.AuditorProvider",
        "sha256": "350a38839b749e238070b2cd585d880d6567252050fd25c31061a026448f1c07"
      }
    },
    {
      "artifact": "axiz-kit-jpa",
      "class": "pe.axiz.core.persistence.jpa.BaseEntity",
      "sha256": "9865a8e5baa90fb8e8f78da4948c8179e2ebdb03d7c6fe54e161a65b1b661d1d",
      "replacement": {
        "artifact": "axiz-kit-jpa",
        "class": "pe.axiz.kit.jpa.BaseEntity",
        "sha256": "2300c58988a28fc037c6988b6c2edc418ee75eb48ef0c6eea8e35576aad1d378"
      }
    },
    {
      "artifact": "axiz-kit-jpa",
      "class": "pe.axiz.core.persistence.jpa.EncryptedConverter",
      "sha256": "555554c0d678a9137f9c2b44e547eca1e1a4bfdae892d07e801cd0a3d5a9704b",
      "replacement": {
        "artifact": "axiz-kit-jpa",
        "class": "pe.axiz.kit.jpa.EncryptedConverter",
        "sha256": "06520ba8262408fbb01255f4bac156786b994581817fd521dceebb60bf893c30"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.DatabasePasswordProvider",
      "sha256": "32f6f4a8a88e91b17d8420332647538254ec62a3cf4e8d8247d2f75e082b85e1",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.DatabasePasswordProvider",
        "sha256": "116587a9fb06127d26563ccdfab49ffd4e4e9579a90cdbac96a13efce845d292"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.EncryptedText",
      "sha256": "35c224348f19e9e3df7b3a8c29c5b0cb5e1daf55559238a8a4d0ad50cccd153f",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.EncryptedText",
        "sha256": "893c2f17df16a1a97d1523cd229ecdb92e85d361bd1ffdc8a263117fcdd7f32e"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.NamedSql",
      "sha256": "ea841893ef302e452f07c1405d56a921dae60b6eacbf480a83bb9d88e84ce393",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.NamedSql",
        "sha256": "d90ea5380ea07073d858372af259f6593a30a4347ba10b2e3c455174647f2f35"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.R2dbcRow",
      "sha256": "6226f1db1854d5ca242b52b2bf16ff5a7a048d21365342e491f31438e128e66d",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.R2dbcRow",
        "sha256": "651c6882118febbffe9e6db350ef1d3606329b0b9d39993363480b9b3193ad50"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.R2dbcSql",
      "sha256": "21c8634b29e44d455bd8b6834b60f72c7554e71dfa9adc3063e7cfbf36e9909a",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.R2dbcSql",
        "sha256": "7434625bac26d35b1f9fe541033014c3ed7088e2f32934854a020f96a9e0c01f"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.R2dbcTransactionRunner",
      "sha256": "089461806971ac90e53b7b07f045a139ca876bf0abb017c4dadcc7a09de1eed2",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.R2dbcTransactionRunner",
        "sha256": "00404afb741bc8d657c6881c02dd1fb8824075bbe297e9e427292e3cec637ea7"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.R2dbcUrls",
      "sha256": "f90261a2ecf55327cb5e5d9f53da22466036af8f8ea3f672434db88aa45f11c7",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.R2dbcUrls",
        "sha256": "e6a241da536bb2b88fd7640c6fb29ba00d37e93302fc5a4ba91c75707ce2f1b1"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.R2dbcUrls$Endpoint",
      "sha256": "92a64129a0334a812d94ccf6ce385d5456f7d3a9a8c0d99b81d621d359307a9f",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.R2dbcUrls$Endpoint",
        "sha256": "f19eb2d59c53f1ffa016f307f66f8978a7eb136c6267c232d23b94300cfa9354"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.RdsIamPasswordProvider",
      "sha256": "6f17634d0cd74820ae8ef9c4d48e2fa006f38666cfd06419e82e035d40089e79",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.RdsIamPasswordProvider",
        "sha256": "ed7a54e03bc343b9e625b75b0cb4143f4a71a30a0b3701f45922106381878d42"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.ReactiveSql",
      "sha256": "35b169f230e2a0e4a90015c0e8abd2323cc6cba2d9291d1052b70d5b8e58c4ad",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.ReactiveSql",
        "sha256": "6bbb6c85d432d298a91d46865f85ffd2bdf2db7e4517a44c1f0d22b2c7489b40"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.RowMapper",
      "sha256": "51bde40f42bb67060d19f51e91f7d9ab1d8a9df3a52f53cc419ae240a92b0131",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.RowMapper",
        "sha256": "68e32c5e9af7fa0672b478299443f9f5d3f66fa73da45b8c70d19413dd6b0441"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.SqlParams",
      "sha256": "0e8b2aef052e2ecbef3dee0a7149fbc3ec63283a7260358517dfcaa99c938e54",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.SqlParams",
        "sha256": "fc8724f6b5b042e5015258c22e4b19e109cd6f742e327abc4930289378b11f50"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.SqlParams$Param",
      "sha256": "e434192ab323590a43bae2ee2f629e85f18d9e51c9316ed036705c883d2303a4",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.SqlParams$Param",
        "sha256": "a14353b8455daa52b2542e569946e742e1d98924fdc958f887997ed37cedcc02"
      }
    },
    {
      "artifact": "axiz-kit-r2dbc",
      "class": "pe.axiz.core.persistence.r2dbc.SqlRow",
      "sha256": "f9d879a679f32ea6b2132c9f01511122f42fddb37b4cbd114fdd191b0b419833",
      "replacement": {
        "artifact": "axiz-kit-r2dbc",
        "class": "pe.axiz.kit.r2dbc.SqlRow",
        "sha256": "f5b3184e9522c37da821b028531d384d2f1dc7adc6b8895cb0edec00af3bee94"
      }
    },
    {
      "artifact": "axiz-archunit-rules",
      "class": "pe.axiz.quality.archunit.AxizArchitecture",
      "sha256": "744535badf6d057e5af5d0f75177fa7b950204237a87e4e4aedd32b670547a9b",
    },
    {
      "artifact": "axiz-archunit-rules",
      "class": "pe.axiz.quality.archunit.AxizArchitecture$Style",
      "sha256": "256a68307c574dbd4d509a7ef4509edd94dc4509ea0ddb279d17152351e5823b",
      "replacement": {
        "artifact": "axiz-archunit-rules",
        "class": "pe.axiz.quality.archunit.Structure",
        "sha256": "155a6f8019df704a5c6f0f8ef86845bfb4f76b3239e563e43cb20dd3573849e5"
      }
    },
    {
      "artifact": "axiz-store-contract-tests",
      "class": "pe.axiz.core.contracts.ContractCheck",
      "sha256": "f45b6c6d6b4a02a0310064a75c425b377d9119a2ab65a9c1abb949e129af5475",
      "replacement": {
        "artifact": "axiz-store-contract-tests",
        "class": "pe.axiz.test.contracts.ContractCheck",
        "sha256": "0672042493d714438b6cd236c3f2ce4e4e8dec95d8c8fa8ea9877b6f88bd572a"
      }
    },
    {
      "artifact": "axiz-store-contract-tests",
      "class": "pe.axiz.core.contracts.ContractCheck$Body",
      "sha256": "fe3286ab792f18db7d80b8c264d31deeb37acfd577afad8d3c4a9b5fab3ffc37",
      "replacement": {
        "artifact": "axiz-store-contract-tests",
        "class": "pe.axiz.test.contracts.ContractCheck$Body",
        "sha256": "11efe6088d4448038ef6b48160d88cc22b2917e70639283774a6f5d36711b4cd"
      }
    },
    {
      "artifact": "axiz-store-contract-tests",
      "class": "pe.axiz.core.contracts.IdempotencyStoreContract",
      "sha256": "807474b5bd8d4a512046b11b2592bcc619e6aacc89813b0172194c18039d3d36",
      "replacement": {
        "artifact": "axiz-store-contract-tests",
        "class": "pe.axiz.test.contracts.IdempotencyStoreContract",
        "sha256": "cdae22f61abf59a142388b80ca115f34c1aff5d9ac0658b7d8d6bb0ef7f028ff"
      }
    },
    {
      "artifact": "axiz-store-contract-tests",
      "class": "pe.axiz.core.contracts.IdempotencyStoreContract$Backend",
      "sha256": "c8df104a4d26a04678dbc39de720650dfa704f06144db1f0e8b131159a8dabea",
      "replacement": {
        "artifact": "axiz-store-contract-tests",
        "class": "pe.axiz.test.contracts.IdempotencyStoreContract$Backend",
        "sha256": "db71efa46ee4b58308caa1fe4bd780064bded656eec174a737c9b9a91dbf61ae"
      }
    },
    {
      "artifact": "axiz-store-contract-tests",
      "class": "pe.axiz.core.contracts.InboxStoreContract",
      "sha256": "d5560104082ebf5a9a0f84290a9983705a97c79f42d5a80a498fb8bfa46091d7",
      "replacement": {
        "artifact": "axiz-store-contract-tests",
        "class": "pe.axiz.test.contracts.InboxStoreContract",
        "sha256": "c30520a4afdb0a2fae0cd7f760b56aa95a14752c2d8b28d66898a2c4c1612ab3"
      }
    },
    {
      "artifact": "axiz-store-contract-tests",
      "class": "pe.axiz.core.contracts.InboxStoreContract$Backend",
      "sha256": "15ba37c1269acd10254991401facf6cb5834b6cc93b76a1e2b27bc9004dd84e9",
      "replacement": {
        "artifact": "axiz-store-contract-tests",
        "class": "pe.axiz.test.contracts.InboxStoreContract$Backend",
        "sha256": "21e7425bd88203d6801f50ef19875fa1c045419a4f8de90c4ef22de77207b063"
      }
    },
    {
      "artifact": "axiz-test-support",
      "class": "pe.axiz.core.test.MetricAssertions",
      "sha256": "554a40efa53a82e91160a7b3c6cce685b7c17bccbfca58272c8a2541251f7808",
      "replacement": {
        "artifact": "axiz-test-support",
        "class": "pe.axiz.test.support.MetricAssertions",
        "sha256": "ed9e76b0775a24c7157828ee17d31d919694c817d2d55d83665b05ffef67dd4d"
      }
    },
    {
      "artifact": "axiz-test-support",
      "class": "pe.axiz.core.test.MutableClock",
      "sha256": "a56fe7a5444f4f690408729ff8f9c9a5a0ada068487b56ec6c68e62ee0b49a46",
      "replacement": {
        "artifact": "axiz-test-support",
        "class": "pe.axiz.test.support.MutableClock",
        "sha256": "5277477c689839d85c57c373300b9fef33981db412a93f7fd7d99c487ab295d4"
      }
    },
    {
      "artifact": "axiz-test-support",
      "class": "pe.axiz.core.test.r2dbc.FakeR2dbc",
      "sha256": "18fd18ce42c827dc00c6672b223055736f4764916e77c3aac9ce41b777464496",
      "replacement": {
        "artifact": "axiz-test-support",
        "class": "pe.axiz.test.support.r2dbc.FakeR2dbc",
        "sha256": "1aad16eef0e739f8db3cabdc1afbc341f9f6c04a8259ae93f4c44e18a4a29f71"
      }
    },
    {
      "artifact": "axiz-test-support",
      "class": "pe.axiz.core.test.r2dbc.FakeR2dbc$Execution",
      "sha256": "4f240d859189b01a231da7ee7f1af7e4488e29e2ef462ae3642b2e0441bd9f4d",
      "replacement": {
        "artifact": "axiz-test-support",
        "class": "pe.axiz.test.support.r2dbc.FakeR2dbc$Execution",
        "sha256": "68eb9822db737d8be1700f715869dfa6f9c1b041543f153a4e77ebcafa3fd4fe"
      }
    },
    {
      "artifact": "axiz-test-support",
      "class": "pe.axiz.core.test.RecordingEventPublisher",
      "sha256": "61d961b2b5a2573a9158b331dacad9e9eeb609cb6b07dcee1e5a79fd7545e57a",
      "replacement": {
        "artifact": "axiz-test-support",
        "class": "pe.axiz.test.support.RecordingEventPublisher",
        "sha256": "81be2a8d92923a7f3c38dfae2dfab343027c2fa67bcc25fb414d66ca1f7ee30e"
      }
    },
    {
      "artifact": "axiz-test-support",
      "class": "pe.axiz.core.test.RecordingEventPublisher$Published",
      "sha256": "2daf64e335ef6d4968d44b170e9c2e883f629de524c4d1ac88a3f76929e6a574",
      "replacement": {
        "artifact": "axiz-test-support",
        "class": "pe.axiz.test.support.RecordingEventPublisher$Published",
        "sha256": "4905843ffbdc6d4ee635c74db48541cab3524f013adcc8f650322ccb3843974c"
      }
    },
    {
      "artifact": "axiz-config-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.config.AxizConfigProperties$ParameterStore",
      "sha256": "31d70580909fbb3709fcbf5a1dca0c941b177d6552cf7e966e092830cdb94064",
    },
    {
      "artifact": "axiz-dynamodb-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.dynamodb.DynamoRepositories",
      "sha256": "d76b3a76a4c8d2639105de053aed16ddef05e7e43e626497f3f6d14a224aadaf",
    },
    {
      "artifact": "axiz-foundation-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.foundation.jdbc.SpringConnectionProvider",
      "sha256": "80500e69448bdbe851bb6e12395f5f4a9874920144f755518fcc357bd382c3d5",
      "reason": "M8/M9: implementa ConnectionProvider de axiz-kit-jdbc, en lugar del antiguo paquete JDBC de core. Su constructor DataSource y el comportamiento de conexión ligada a la transacción se conservan."
    },
    {
      "artifact": "axiz-foundation-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.foundation.jdbc.SpringTransactionRunner",
      "sha256": "48426151eef87c1b6a33671fac8bf3ffef0303bce56300e0fee6b917ec7d77c0",
      "reason": "M8/M9: implementa TransactionRunner de core.commons.tx; conserva el constructor PlatformTransactionManager y la transacción Spring. La receta cambia el import del puerto."
    },
    {
      "artifact": "axiz-idempotency-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.idempotency.AxizIdempotencyProperties",
      "sha256": "e4b08f99ae79e3df8b01f785b10b62b1919f8f87bf69a68aadc68a840b78ad7c",
      "reason": "A2/A3: el constructor canónico incorpora clientRetryWindow y valida retención >= ventana de reintento + lease. El binding anterior por nombre conserva defaults seguros; construcciones Java deben pasar la ventana."
    },
    {
      "artifact": "axiz-idempotency-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.idempotency.IdempotencyWebFilter",
      "sha256": "3422c3e1f6106ff6f04a9d8a90fd81132fcf8d36f72ca371ff7ac91e2d152160",
    },
    {
      "artifact": "axiz-inbox-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.inbox.AxizInboxProperties",
      "sha256": "8756f1775fd23e1a91c51f5af5b944322ab988d6b0eaec5f47913373eeab6fc9",
      "reason": "M2: el constructor canónico incorpora transactional=auto/true/false. El binding por nombre usa auto por defecto; construcciones Java deben declarar el modo y compartir la identidad del recurso para atomicidad."
    },
    {
      "artifact": "axiz-jpa-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.jpa.AuditingRegistrar",
      "sha256": "3ecd7323e2a4c9dfff07fcf21f3d8a238813ed5b93ad4daf19d406bf29ba94e9",
    },
    {
      "artifact": "axiz-jpa-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.jpa.RequestAuditorProvider",
      "sha256": "583fea27e00f7872aad587b879b158cd05ceceb7a076f37379793269bfde45fb",
    },
    {
      "artifact": "axiz-messaging-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.messaging.AxizMessagingProperties",
      "sha256": "3720923424f7b85f056168f94ec1c83c4d6fd2d2b7fb76c741f2b95f16593852",
    },
    {
      "artifact": "axiz-messaging-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.messaging.AxizMessagingProperties$Consumer",
      "sha256": "6d3cdd3e25e938c412fec7999cb29e95b52ed975dc2caf9f0be002a7be2fa2bd",
    },
    {
      "artifact": "axiz-outbox-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.outbox.ReactiveTransactionalEvents",
      "sha256": "b77337b5b77b8dc64db88004eb015199736d2044967983cd59e6ac695efe66d3",
    },
    {
      "artifact": "axiz-r2dbc-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.r2dbc.AxizR2dbcConverters",
      "sha256": "bcdfbc65780fcef3421fcc4c35c56752d741a21f1407267dbbc605482f9117d9",
      "reason": "M6: elimina CONTEXT genérico y los all() sin contexto; usar all(context) o all(supplier,context). axiz.field solo se lee mediante migración explícita; no se escribe implícitamente."
    },
    {
      "artifact": "axiz-r2dbc-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.r2dbc.AxizR2dbcConverters$EncryptedTextReader",
      "sha256": "e00148d736611e1851625de112f2dd13b7fab777132d03fee959d92b734e7c78",
    },
    {
      "artifact": "axiz-r2dbc-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.r2dbc.AxizR2dbcConverters$EncryptedTextWriter",
      "sha256": "bd1d575c3b736d98ac16e116bd016c21cd2346078dfe15fa9a864ef72e30cc71",
    },
    {
      "artifact": "axiz-r2dbc-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.r2dbc.DatabaseClientReactiveSql",
      "sha256": "3ded83417f37c8aee5238c654d8fe3d80b09b3fe74e514a965a366194cc10ada",
      "reason": "M2/M8: las firmas SQL usan los tipos del kit y se añade transactionResource() con la ConnectionFactory real. El postprocesador compara identidad del recurso sin introspección privada."
    },
    {
      "artifact": "axiz-r2dbc-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.r2dbc.FlywayIamCustomizer",
      "sha256": "b42959598c0ed70272b5afcb68a9f69ac1ea412f6ca666063b01dcc7ab3cb50d",
    },
    {
      "artifact": "axiz-r2dbc-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.r2dbc.ReaderDatabase",
      "sha256": "d4daf585043281c75e7f2e09430442fc41688085132b40771305a26f8f24cc9f",
    },
    {
      "artifact": "axiz-security-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.security.AxizSecurityProperties$RateLimit",
      "sha256": "9e5dfbf21111a2d7c3e504bc6bce6bb581bba7580a1c8f5c14c5eb7047dcb9e4",
      "reason": "M4: elimina el failOpen global y su argumento del constructor; cada regla habilitada debe declarar su política. La receta elimina la propiedad global y conserva las políticas por regla."
    },
    {
      "artifact": "axiz-security-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.security.AxizSecurityProperties$Rule",
      "sha256": "7d6110e12dc707f75a0737e91befc2e88b2d9fbf120ed5ac3112474baa5ceece",
      "reason": "M4: reemplaza failOpen(defaultGlobal) por failurePolicy(), que requiere una política explícita. El campo Boolean distingue falta de configuración de false."
    },
    {
      "artifact": "axiz-security-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.security.RateLimitFilter",
      "sha256": "269c99b61007cea43acd9463ccdef430b0627ac2d274c9e557ee4f6c6e9420f8",
      "reason": "M4: elimina el argumento global failOpen del constructor; cada ruta usa su regla. Cuota agotada conserva 429 y almacenamiento indisponible con fail-closed devuelve 503."
    },
    {
      "artifact": "axiz-security-spring-boot-autoconfigure",
      "class": "pe.axiz.spring.security.RateLimitWebFilter",
      "sha256": "d07bcd1bfd69f5e1fc5fcd435e74dccec0f3389531c4c9108733fb85df306035",
    },
    {
      "artifact": "axiz-structure-batch-api",
      "class": "pe.axiz.framework.batch.CsvRecordReader",
      "sha256": "df3abcce2baec7d41b7545651870f6d9d09a890a7257f95f75e4e8cc35e79bb4",
      "replacement": {
        "artifact": "axiz-structure-batch-api",
        "class": "pe.axiz.structure.batch.CsvRecordReader",
        "sha256": "bfa1a372c091ec988fb827f64b4d30245a858f6ccc5cb1123919c41cee447885"
      }
    },
    {
      "artifact": "axiz-structure-batch-api",
      "class": "pe.axiz.framework.batch.FixedWidthRecordReader",
      "sha256": "33a4fc2f0371807e41a03948c10229d53ff75ab503edea1ff835d90fbccbed79",
      "replacement": {
        "artifact": "axiz-structure-batch-api",
        "class": "pe.axiz.structure.batch.FixedWidthRecordReader",
        "sha256": "62f47ebad12b917434727468e9687ff82c501181ae39c945584c3fd8963b2e66"
      }
    },
    {
      "artifact": "axiz-structure-batch-api",
      "class": "pe.axiz.framework.batch.JobOutcome",
      "sha256": "85cb8b814212c570ffa431729b7df10ae32add3015e90d0ec7640083e6216583",
      "replacement": {
        "artifact": "axiz-structure-batch-api",
        "class": "pe.axiz.structure.batch.JobOutcome",
        "sha256": "9fdd1600e0110d41c4f0cca42e984019449dc243d6689c89db3f89d293422985"
      }
    },
    {
      "artifact": "axiz-structure-batch-api",
      "class": "pe.axiz.framework.batch.RecordReader",
      "sha256": "191c9987c9e5e7d1aea452324a96be00be72b961904a2ec995014cceca57255f",
      "replacement": {
        "artifact": "axiz-structure-batch-api",
        "class": "pe.axiz.structure.batch.RecordReader",
        "sha256": "024ef48b4676259535492fd55c6ebfa434bdaf6112a30465f89e25281adff420"
      }
    },
    {
      "artifact": "axiz-structure-batch-spring-boot-starter",
      "class": "pe.axiz.framework.batch.spring.AxizBatchAutoConfiguration",
      "sha256": "c5fde6b19671ca82278c62948ac58e631aa4ee347ebe441be17e7de77ce3bc26",
      "replacement": {
        "artifact": "axiz-structure-batch-spring-boot-starter",
        "class": "pe.axiz.structure.batch.spring.AxizBatchAutoConfiguration",
        "sha256": "b2e124e4516f1f47b75adf147edf7bd098dcba07b2240875537e1c22a7771d0f"
      }
    },
    {
      "artifact": "axiz-structure-batch-spring-boot-starter",
      "class": "pe.axiz.framework.batch.spring.AxizBatchEnvironmentPostProcessor",
      "sha256": "6340ba46e172c2b829c6f7cfc5284eb50ed6e3504f25f8064c168c6e5b16faa2",
      "replacement": {
        "artifact": "axiz-structure-batch-spring-boot-starter",
        "class": "pe.axiz.structure.batch.spring.AxizBatchEnvironmentPostProcessor",
        "sha256": "abac7d69994a411cdb4e889601302ad798d2ef54cd5c5f8bf17970302de29e21"
      }
    },
    {
      "artifact": "axiz-structure-batch-spring-boot-starter",
      "class": "pe.axiz.framework.batch.spring.ObservedJobListener",
      "sha256": "61e369153e6000ccd079b85aacdf3ea63b79c6bd370323fa4985a7b5d5245db8",
      "replacement": {
        "artifact": "axiz-structure-batch-spring-boot-starter",
        "class": "pe.axiz.structure.batch.spring.ObservedJobListener",
        "sha256": "d489a1fbb767dff09adcf2165d91c0a9419d159161ca335bc50a6db91b9ba715"
      }
    },
    {
      "artifact": "axiz-structure-batch-spring-boot-starter",
      "class": "pe.axiz.framework.batch.spring.ObservedJobListenerPostProcessor",
      "sha256": "bba777ef046f9e79b842ee368e0b30bee79a376eb3981a9965995f607afd5ba6",
      "replacement": {
        "artifact": "axiz-structure-batch-spring-boot-starter",
        "class": "pe.axiz.structure.batch.spring.ObservedJobListenerPostProcessor",
        "sha256": "d3bb2785a0d44cc8c6ec8ca6eb56ec32432efde2e0de1c01c9fd1c88abe4cf3a"
      }
    },
    {
      "artifact": "axiz-structure-batch-spring-boot-starter",
      "class": "pe.axiz.framework.batch.spring.StorageRecordItemReader",
      "sha256": "0e1597fefa0174849ce1a178304d8a381e63b1b4e29fadb06115763822762c1e",
      "replacement": {
        "artifact": "axiz-structure-batch-spring-boot-starter",
        "class": "pe.axiz.structure.batch.spring.StorageRecordItemReader",
        "sha256": "addf1e2a9a8b25d414c460cc08768a63586d382eeff84f9d9677a5693eb0b460"
      }
    },
    {
      "artifact": "axiz-structure-bff-api",
      "class": "pe.axiz.framework.bff.ChannelAdapter",
      "sha256": "c7504027aba3a62be43ae14c965a9b58d23c162c189d4fadf8ecdeffa8c120fa",
      "replacement": {
        "artifact": "axiz-structure-bff-api",
        "class": "pe.axiz.structure.bff.ChannelAdapter",
        "sha256": "4b5c0ed404972516ded8b4cffa5ff007a6f7abf1ceac5814c0ad456904ee1433"
      }
    },
    {
      "artifact": "axiz-structure-bff-api",
      "class": "pe.axiz.framework.bff.ChannelAdapters",
      "sha256": "8327d39ed07b6a4bc4d35cbc52b4c1c27d654b9f21d608d6f163388e81115851",
      "replacement": {
        "artifact": "axiz-structure-bff-api",
        "class": "pe.axiz.structure.bff.ChannelAdapters",
        "sha256": "08a3061e688c397c95fba243680d078445b53c4e7eb98c3d721076e3a60f6fb0"
      }
    },
    {
      "artifact": "axiz-structure-bff-api",
      "class": "pe.axiz.framework.bff.Composer",
      "sha256": "17f8d1a61328dfae61cfbfa1d7f52dcb6b352f0ca429ff8473ce32b54791f57b",
      "replacement": {
        "artifact": "axiz-structure-bff-api",
        "class": "pe.axiz.structure.bff.Composer",
        "sha256": "928cd857fe09f5bbbd0fcfaa4bea77b72a09d92d2082f29f034cfdb91652c833"
      }
    },
    {
      "artifact": "axiz-structure-bff-api",
      "class": "pe.axiz.framework.bff.Composition",
      "sha256": "ded24115c2cf6030c41d886e820a47ce3456312ffcea4efa67c05b5a47eef3e8",
      "replacement": {
        "artifact": "axiz-structure-bff-api",
        "class": "pe.axiz.structure.bff.Composition",
        "sha256": "3cf288b665caef237b863fdb1d6f0e85d8fd11a49f542a0447b9f526ed9a2e22"
      }
    },
    {
      "artifact": "axiz-structure-bff-spring-boot-starter",
      "class": "pe.axiz.framework.bff.spring.AxizBffAutoConfiguration",
      "sha256": "9ef2ec8feff3649de993ba7ccded4b2a95e8bd6ff2bfc16ca592d51f7badd31c",
      "replacement": {
        "artifact": "axiz-structure-bff-spring-boot-starter",
        "class": "pe.axiz.structure.bff.spring.AxizBffAutoConfiguration",
        "sha256": "b98075b9be8ed5e79890d4a69a45aff7ba3a7b8c4fbc421b141a77ca253eed49"
      }
    },
    {
      "artifact": "axiz-structure-bff-spring-boot-starter",
      "class": "pe.axiz.framework.bff.spring.Compositions",
      "sha256": "c244120a97528d48da4b035fef25afe2bdd5d8bfeb4b7596dad1b904a3b2ff24",
      "replacement": {
        "artifact": "axiz-structure-bff-spring-boot-starter",
        "class": "pe.axiz.structure.bff.spring.Compositions",
        "sha256": "828e554fa208fd2ac6fa41f941c9a20d5577664c3f0b52f115842c4366d14e9f"
      }
    },
    {
      "artifact": "axiz-structure-bff-spring-boot-starter",
      "class": "pe.axiz.framework.bff.spring.ReactiveComposer",
      "sha256": "eb5d2920dcf28988ce1f4d71420be4e791ebf5889f8861d1564b694dde95eeed",
      "replacement": {
        "artifact": "axiz-structure-bff-spring-boot-starter",
        "class": "pe.axiz.structure.bff.spring.ReactiveComposer",
        "sha256": "dd498142849dbfe2f71f3a09b1bbc377d77e2a9d0abb284db490cbb83d6544f1"
      }
    },
    {
      "artifact": "axiz-structure-eda-api",
      "class": "pe.axiz.framework.eda.EventRoutes",
      "sha256": "21f16c6d9694fc38313312ff4cbe890de386fb760f0f6a9260ab3527fc54b0c3",
      "replacement": {
        "artifact": "axiz-core-messaging",
        "class": "pe.axiz.core.messaging.EventRoutes",
        "sha256": "5b3754d56b8c07475654b590b96de0824acebfe2873a111da7c831df1500d661"
      }
    },
    {
      "artifact": "axiz-structure-eda-api",
      "class": "pe.axiz.framework.eda.HandlesEvent",
      "sha256": "7d24472516a452a9e7d0a238c98aa0499527300786937e1985d12b35a4c959f5",
      "replacement": {
        "artifact": "axiz-core-messaging",
        "class": "pe.axiz.core.messaging.HandlesEvent",
        "sha256": "f7fa04a955553d7f620b5a19e23eee8b24892a46bf02641ded7aa0f396fdb04f"
      }
    },
    {
      "artifact": "axiz-structure-eda-api",
      "class": "pe.axiz.framework.eda.ReactiveTypedEventHandler",
      "sha256": "a36ca0b14d8477130b788a09a8809e2c9633fd3de37c2150864a438afe4a3ad5",
      "replacement": {
        "artifact": "axiz-messaging-spring-boot-autoconfigure",
        "class": "pe.axiz.spring.messaging.ReactiveTypedEventHandler",
        "sha256": "af38c78feb257e6000b470260355e960e4ce1543d4b4a35d350e9d1b1dd2c86e"
      }
    },
    {
      "artifact": "axiz-structure-eda-api",
      "class": "pe.axiz.framework.eda.TypedEventHandler",
      "sha256": "78d929e7430a347c55790f23fc3171c2794ea481cdb965fe757ffcdaabb0b7e1",
      "replacement": {
        "artifact": "axiz-core-messaging",
        "class": "pe.axiz.core.messaging.TypedEventHandler",
        "sha256": "0408e5ede3ef7dbc8c8bcc7d9965279dec502b05ebc6303762210fe58abc86e9"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.AxizEdaAutoConfiguration",
      "sha256": "de4e69336acd9fd012240df6991b9292379f359f08dc7e969396e03978deb6dd",
      "replacement": {
        "artifact": "axiz-structure-eda-spring-boot-starter",
        "class": "pe.axiz.structure.eda.spring.AxizEdaAutoConfiguration",
        "sha256": "4ba13d4e09daffd496980dfc91bc23bbcb122bb43388b66808160634974ad646"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.AxizEdaProperties",
      "sha256": "e655d54e2e562140b023e1f27d8aa9bdcbfddf65219b7cbb71347575b5f447de",
      "replacement": {
        "artifact": "axiz-messaging-spring-boot-autoconfigure",
        "class": "pe.axiz.spring.messaging.AxizMessagingProperties",
        "sha256": "3720923424f7b85f056168f94ec1c83c4d6fd2d2b7fb76c741f2b95f16593852"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.Reactions",
      "sha256": "20ad4b3d7d79c0242b8a9eb010ee2e2e56a75526dbc125c96321607359516995",
      "replacement": {
        "artifact": "axiz-structure-eda-spring-boot-starter",
        "class": "pe.axiz.structure.eda.spring.Reactions",
        "sha256": "2f50360af39579c97b05bc9a48528710d2d7f8fe03a271b8495c28ec28c6b752"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.ReactiveEventHandlerPostProcessor",
      "sha256": "49f6290196b1b57c89c7c2d191f405ba15dd697f951556ba0a913b1a091d2193",
      "replacement": {
        "artifact": "axiz-messaging-spring-boot-autoconfigure",
        "class": "pe.axiz.spring.messaging.ReactiveEventHandlerPostProcessor",
        "sha256": "6a6ff3744d3a2a45f81e2607e84fa3b1a215e0698f24759c79e0599a96543734"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.ReactiveReactions",
      "sha256": "4122f921e38c0d4a740361df3288801693f147149e2a2770847197b66e2246ed",
      "replacement": {
        "artifact": "axiz-structure-eda-spring-boot-starter",
        "class": "pe.axiz.structure.eda.spring.ReactiveReactions",
        "sha256": "e3cdfcf120ea5cbdb224a3467f74a28f4b66a57fea15d37662f6f6d04c2e469e"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.ReactiveTransactionalEventHandler",
      "sha256": "50b1108c713f678bc2e5d1f593aea5951b41364efc7fac9b0555a0ce5082efc2",
      "replacement": {
        "artifact": "axiz-messaging-spring-boot-autoconfigure",
        "class": "pe.axiz.spring.messaging.ReactiveTransactionalEventHandler",
        "sha256": "d20eb6bf07b51d714c45eac2b1449f6f89a61c4802d74f5408ba7a048f76a8ba"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.TransactionalEventHandler",
      "sha256": "710033274d1e39b77d3fac2b566f8aa66595c7fdc0fbca22a19425d6816ec82d",
      "replacement": {
        "artifact": "axiz-messaging-spring-boot-autoconfigure",
        "class": "pe.axiz.spring.messaging.TransactionalEventHandler",
        "sha256": "37bb53675e675ff0ecf1df5994aec4b36a067d26ecda728e349498c1328e7379"
      }
    },
    {
      "artifact": "axiz-structure-eda-spring-boot-starter",
      "class": "pe.axiz.framework.eda.spring.TransactionalEventHandlerPostProcessor",
      "sha256": "3d67615bb3220c20c08fad151f904d72d9bccf27659e4500198f9dab52b7d7a1",
      "replacement": {
        "artifact": "axiz-messaging-spring-boot-autoconfigure",
        "class": "pe.axiz.spring.messaging.TransactionalEventHandlerPostProcessor",
        "sha256": "d16730d70a19c1b6d5501113a8bbbde8c1282c34d226aca5562ccb5b909a9001"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.AggregateEvent",
      "sha256": "2bd5bca3b7f29ea0bfad1e1c12926eb7fff4fedbcb879c6bd4c459119c6fc005",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.AggregateEvent",
        "sha256": "9a24ec541107d3ee75a1aad72d34fbb1c0cf7d55af34cfe37366ed58b9e46117"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.annotation.Adapter",
      "sha256": "a6901d1219b7891f9bb2e66138d8424f2d41d9df3339221919b559cc5daf3bd4",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.annotation.Adapter",
        "sha256": "ea64282ae6db35263e9ddbafdef0f12db091a889fc88a3f15426d2ef9f52213e"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.annotation.InboundPort",
      "sha256": "1635f2c3ee198a41c18049fc2f6c6d686557d8be9bb1dca324926ecf50bd0912",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.annotation.InboundPort",
        "sha256": "ab63229ebb21eed5a80c39b0c7fb9df24ae0a0787001c50c9673fbc8de5d6b49"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.annotation.OutboundPort",
      "sha256": "abb0fcebbc8157ba3a4441c92dc4ad8d57ddfb1cd38d55dedbbe5b0297ae0de5",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.annotation.OutboundPort",
        "sha256": "2efbb5fc68b19be5d0eb59117932c1d9102fb8a61884138c0cd4cf30c34d9948"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.annotation.UseCase",
      "sha256": "f9ea7f645ce005c1672c9e81ab6dc05374eb56a1f9434852dec98841a79f2f06",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.annotation.UseCase",
        "sha256": "efde0dc6a8ac3a8cdaf5a39893144fdf4a63d0162c9259962bf9bd561e030495"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.DomainEvents",
      "sha256": "60ea173ec163d9b9fc279d1390aa788381ef4149d08633f3a3196270b9002da8",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.DomainEvents",
        "sha256": "a53a427902abaa8562c28abcf83580c71b2e45e2df4ef177487e34298417bc1d"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.DomainEventSink",
      "sha256": "05db37fa27a31980410dc2d5100bcc19d43f595665230ecd18e0cd7afbd6f052",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.DomainEventSink",
        "sha256": "e5548143c7e12dc0da0cb23add28e7846e4f027da2bd11e21f74c8720dba8666"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.UseCase",
      "sha256": "5c0a4e8a01b39241c6869d0abd900143345a60a3d2a798aae112cce26bb07651",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.UseCase",
        "sha256": "ad00389a9c5675f6cc9cf3abee48882f76ae49bf4efe239cbe563a5972a378df"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.UseCaseRunner",
      "sha256": "785533d7e56e7f0291905d2fd911db45204849a116e3bb7a0ebbdd04339f2cd2",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.UseCaseRunner",
        "sha256": "9b058b5fbd71368365cccd928bc7034361eac49a85fb76d24b00bc415beab3d2"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-api",
      "class": "pe.axiz.framework.hexagonal.UseCaseTransaction",
      "sha256": "b65dd187b173b14c905ca17b80184ca29f92c1207ddf6c3d4b62f1c205786460",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-api",
        "class": "pe.axiz.structure.hexagonal.UseCaseTransaction",
        "sha256": "9a3c19377c09bad9c596916642a9a0c4934efd9322150ae5be496ad95013023e"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.ApplicationEventDomainEventSink",
      "sha256": "321eb81ad5b7400537626099c6f18b3f75926fc17ba3af516b796cde5ae53f76",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.ApplicationEventDomainEventSink",
        "sha256": "88238c61f1d379ed56215c10dac428535c3cb5f0b76a14543264548b6e70b015"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.AxizHexagonalAutoConfiguration",
      "sha256": "5604799b81644665f5af8042893d781006088188758bf4691e1bb9d064e39c43",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.AxizHexagonalAutoConfiguration",
        "sha256": "858ed2d3f84bfdc08b6eb5748a9aba3d3ae12853978ba13f9c494e7a162ee723"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.AxizHexagonalProperties",
      "sha256": "465b49ec13995818fa17d2689b2d328508910246c72e89e0b8bb12084e2e9544",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.AxizHexagonalProperties",
        "sha256": "760b5eaa350c1a14661bbb512e0d03181c660d128523b8886c395e8defd0afdc"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.AxizHexagonalProperties$Events",
      "sha256": "d8d59ec1040d91fb4b95829878c5496382a409b7e9dd625218dbbbaa3d0a09fa",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.AxizHexagonalProperties$Events",
        "sha256": "1a3a7abbaa24c535399ebe4384fc6896e9e9cf36549166fc0d3310873f5db1ad"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.OutboxDomainEventSink",
      "sha256": "c6886cf4c39fd9dea752d745826970524f27fcb16bac185aa1395e636beaeb9d",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.OutboxDomainEventSink",
        "sha256": "3e806aa1c099d33379f8c8f2dde79af5efe0de371b0b94f68a290f8babf6d402"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.SpringUseCaseTransaction",
      "sha256": "d816ae88e40d244cb0fb26dd329843df651997147b1341f88bb6930d71250cca",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.SpringUseCaseTransaction",
        "sha256": "953cc8a9b6bc8698edc3eed7fce5fd7d3c95c1c3c03189518e56a2d3d0ab1e3e"
      }
    },
    {
      "artifact": "axiz-structure-hexagonal-spring-boot-starter",
      "class": "pe.axiz.framework.hexagonal.spring.UseCaseInterceptor",
      "sha256": "517de6408f68b84101456488b3c26f4ba6a4015c6471bb845486023c51a76172",
      "replacement": {
        "artifact": "axiz-structure-hexagonal-spring-boot-starter",
        "class": "pe.axiz.structure.hexagonal.spring.UseCaseInterceptor",
        "sha256": "0651b89563174e4f7166b3bb35012c4c4d9e755ec3bc06456a8cf82be1045e1d"
      }
    },
    {
      "artifact": "axiz-structure-layered-api",
      "class": "pe.axiz.framework.layered.CommandService",
      "sha256": "8601ee7aa121b980e9b9d3f12c8c8d6521c6f47bb8e6c26264932fd7303a7f39",
      "replacement": {
        "artifact": "axiz-structure-layered-api",
        "class": "pe.axiz.structure.layered.CommandService",
        "sha256": "af6812c3f56383e06d2b0c94d54acd60bfa0fc82cd1961a70f9bad634eb6c96b"
      }
    },
    {
      "artifact": "axiz-structure-layered-api",
      "class": "pe.axiz.framework.layered.CrudService",
      "sha256": "16575328bcf5385b8bc427b76d713ed8b1db2bd27e200b447e6a5beaa77fa6e4",
      "replacement": {
        "artifact": "axiz-structure-layered-api",
        "class": "pe.axiz.structure.layered.CrudService",
        "sha256": "35d755b68472114696a1a391645416ce5e153fa7850e8a736f3c3c1ec03612d3"
      }
    },
    {
      "artifact": "axiz-structure-layered-api",
      "class": "pe.axiz.framework.layered.InMemoryCrudService",
      "sha256": "644497e02ef8455321e30c55818725c79ef259747563424b54f5bebd132db0f6",
      "replacement": {
        "artifact": "axiz-structure-layered-api",
        "class": "pe.axiz.structure.layered.InMemoryCrudService",
        "sha256": "abd3a354223280a670d800fb3f0c1392ee29ba0c2aae4b543411fbe3c1acc1eb"
      }
    },
    {
      "artifact": "axiz-structure-layered-api",
      "class": "pe.axiz.framework.layered.QueryService",
      "sha256": "f924dd2ba7c0fd214d3e9b95d54afa9d8bcc737b5db4f0fb2c98ff6b9126f153",
      "replacement": {
        "artifact": "axiz-structure-layered-api",
        "class": "pe.axiz.structure.layered.QueryService",
        "sha256": "8d8d679567e5689ddbdc948f345318b589134ea047caf3b480b301a86b5b5f66"
      }
    },
    {
      "artifact": "axiz-structure-layered-spring-boot-starter",
      "class": "pe.axiz.framework.layered.spring.CrudController",
      "sha256": "fa61e1bcf239981a0b71734ceb928a411dc5eb26dd6cff7f495645813a523e05",
      "replacement": {
        "artifact": "axiz-structure-layered-spring-boot-starter",
        "class": "pe.axiz.structure.layered.spring.CrudController",
        "sha256": "f7d8658fecaee70079700e6de6bb207eefeb951f75a465d5ca2a1e9ef887eafd"
      }
    },
    {
      "artifact": "axiz-structure-layered-spring-boot-starter",
      "class": "pe.axiz.framework.layered.spring.JpaCrudService",
      "sha256": "0ac4b9f03ebd36aa39c1639fdc0fa485792a9cb0209d88b3098bcbd8b20a1c12",
      "replacement": {
        "artifact": "axiz-structure-layered-spring-boot-starter",
        "class": "pe.axiz.structure.layered.spring.JpaCrudService",
        "sha256": "1bff7467af6c055ec5f4c3be89f8abc7fc5b0b7367a10c881ada5ec932ac035f"
      }
    }
  ]
}
```

La comparación se ejecuta con:

```sh
./mvnw -B -ntp verify -Papi-compat,api-migration -Daxiz.api.baseline=1.0.0
python3 .github/scripts/check-api-compatibility.py --baseline 1.0.0
```

El perfil normal `api-compat` sigue rechazando directamente cualquier ruptura. `api-migration`
solo permite generar los reportes para 2.0.0 frente a 1.x y necesita el control posterior del
inventario. Después de promover 2.0 se utiliza el perfil normal contra la nueva línea base.
