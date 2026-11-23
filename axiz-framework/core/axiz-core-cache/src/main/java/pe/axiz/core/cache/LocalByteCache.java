package pe.axiz.core.cache;

/**
 * Nivel de caché en proceso, solo de CPU. Las implementaciones no deben hacer E/S remota ni de
 * archivos.
 */
public interface LocalByteCache extends ByteCache {}
