package pe.axiz.core.commons.jdbc;

import java.util.concurrent.Callable;

/** Runs work inside a transaction (Spring: {@code TransactionTemplate}). */
@FunctionalInterface
public interface TransactionRunner {

  <T> T inTransaction(Callable<T> work) throws Exception;
}
