package org.gpc4j.docs.config;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Configures the Caffeine-backed {@code searchResults} cache, which stores
 * {@link org.gpc4j.docs.model.SearchResultData} instances keyed on query
 * string and page number. Entries are bounded at 1 000 and expire 5 minutes
 * after write.
 */
@Configuration
@EnableCaching
public class CacheConfig {

  private static final int SEARCH_MAX_ENTRIES = 1_000;

  /**
   * {@link CacheManager} backed by the {@code searchResults}
   * {@link CaffeineCache}.
   *
   * @return the configured cache manager
   */
  @Bean
  public CacheManager cacheManager() {

    var searchCache = Caffeine
      .newBuilder()
      .maximumSize(SEARCH_MAX_ENTRIES)
      .expireAfterWrite(5, TimeUnit.MINUTES)
      .<Object, Object>build();
    var searchResults = new CaffeineCache("searchResults", searchCache);

    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(List.of(searchResults));
    return manager;
  }

}
