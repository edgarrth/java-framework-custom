package pe.axiz.quality.archunit.sample.locks.application;

import java.util.concurrent.locks.ReentrantLock;

/** Incumple las reglas de clúster: un lock local de la JVM y un método de negocio synchronized. */
public class LocalLockTransfer {

  private final ReentrantLock lock = new ReentrantLock();

  public void debit() {
    lock.lock();
    try {
      // mover dinero
    } finally {
      lock.unlock();
    }
  }

  public synchronized void credit() {
    // mover dinero
  }
}
