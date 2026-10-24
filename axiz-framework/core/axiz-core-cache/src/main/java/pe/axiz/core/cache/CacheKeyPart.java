package pe.axiz.core.cache;

/**
 * A value that knows its own cache key: implement it on a value object that is not a record and is
 * used as an argument of a cached method. The key must identify the value completely (two values
 * with the same key are interchangeable for the cache) and must not change between releases unless
 * the cache can be emptied.
 */
public interface CacheKeyPart {

  /** Stable identity of the value; separators are escaped by {@link CacheKeys}. */
  String cacheKey();
}
