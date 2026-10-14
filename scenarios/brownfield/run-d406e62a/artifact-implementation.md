=== FILE: src/main/java/dev/shortener/api/RateLimitFilter.java ===
package dev.shortener.api;
public class RateLimitFilter { /* per-IP token bucket on POST /api/v1/urls, 429 + Retry-After */ }
=== FILE: src/main/java/dev/shortener/repository/ClickDailyQuery.java ===
package dev.shortener.repository;
public interface ClickDailyQuery { /* clicks per day for a short url, from click_events */ }