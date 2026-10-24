package pe.axiz.quality.archunit.sample.locks.application;

import java.util.concurrent.locks.ReentrantLock;

/** Violates the cluster rules: a JVM-local lock and a synchronized business method. */
public class LocalLockTransfer {

  private final ReentrantLock lock = new ReentrantLock();

  public void debit() {
    lock.lock();
    try {
      // move money
    } finally {
      lock.unlock();
    }
  }

  public synchronized void credit() {
    // move money
  }
}
