package pe.axiz.core.commons.tx;

import java.util.concurrent.Callable;

/** Ejecuta trabajo dentro de una transacción (Spring: {@code TransactionTemplate}). */
@FunctionalInterface
public interface TransactionRunner {

  <T> T inTransaction(Callable<T> work) throws Exception;
}
