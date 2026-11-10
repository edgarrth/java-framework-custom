# axiz-adapter-database-auth-aws

AWS RDS/Aurora IAM password tokens implementing `DatabasePasswordProvider` from `axiz-kit-r2dbc`.
The kit retains the neutral contract; this adapter owns the RDS SDK dependency and token signer.

Declare this adapter with `runtime` scope in services using `axiz.r2dbc.iam.enabled=true`.
The R2DBC capability selects it when generating a service; the generic starter keeps this IAM
adapter optional.
Libraries that construct `RdsIamPasswordProvider` directly declare a compile dependency instead.

Spring prepares credentials and the first token during startup. Token generation uses the warmed
credential snapshot; credentials refresh independently of connection acquisition and fail closed on
expiry. The pool resolves a token for each new connection. Reader pools own their token resources
and close them when destroyed. Tests use injectable clocks and synchronization primitives.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-database-auth-aws</artifactId>
  <scope>runtime</scope>
</dependency>
```

The AWS adapter's dependency on the R2DBC kit is an explicit architecture exception because it
implements that kit's credential contract. It does not make the kit depend on AWS.
