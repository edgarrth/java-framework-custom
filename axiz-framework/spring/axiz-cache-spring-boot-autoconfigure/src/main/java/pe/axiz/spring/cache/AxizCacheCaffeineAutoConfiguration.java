package pe.axiz.spring.cache;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pe.axiz.core.cache.ByteCache;

/** L1 on Caffeine when it is on the classpath (the starter brings it). */
@AutoConfiguration
@ConditionalOnClass(name = "com.github.benmanes.caffeine.cache.Caffeine")
@EnableConfigurationProperties(AxizCacheProperties.class)
public class AxizCacheCaffeineAutoConfiguration {

  @Bean
  ByteCache axizL1Cache(AxizCacheProperties p) {
    return new CaffeineByteCache(p.l1().maxEntries());
  }
}
