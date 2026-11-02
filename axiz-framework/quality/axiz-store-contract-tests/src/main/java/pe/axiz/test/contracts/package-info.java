/**
 * Contract checks for store implementations, written in plain Java so they run under any test
 * engine: each check is a named {@link pe.axiz.test.contracts.ContractCheck}; a JUnit test turns
 * them into dynamic tests. The same checks run against in-memory fakes in unit tests and against
 * real backends (PostgreSQL, Valkey, DynamoDB) in integration tests.
 */
@NullMarked
package pe.axiz.test.contracts;

import org.jspecify.annotations.NullMarked;
