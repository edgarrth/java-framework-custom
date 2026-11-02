package pe.axiz.core.cache;

/** A CPU-only, in-process cache tier. Implementations must perform no remote or file I/O. */
public interface LocalByteCache extends ByteCache {}
