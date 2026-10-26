package pe.axiz.core.cache;

/**
 * Who shares the entries of an {@link AxizCacheable} method, on top of its arguments.
 *
 * <p>The default is {@link #TENANT}: in a multi-tenant service two tenants never read each other's
 * entries, even when a method takes a tenant-local id (a customer code, a page number) and reads
 * the tenant from the context. {@link #GLOBAL} is a deliberate choice for data that is the same for
 * everyone (a catalog, a list of banks).
 */
public enum CacheScope {

  /**
   * Entries are shared only by calls of the same tenant (the one bound to the call, or none in a
   * single-tenant service).
   */
  TENANT,

  /**
   * Entries are shared only by calls of the same tenant and the same authenticated caller, for
   * results that depend on who asks. Without an authenticated caller the method runs without the
   * cache.
   */
  CALLER,

  /** Entries are shared by every caller and tenant: only for data that is truly global. */
  GLOBAL
}
