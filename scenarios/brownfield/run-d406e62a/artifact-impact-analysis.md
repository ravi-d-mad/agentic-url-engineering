# Impact analysis (grounded in the codebase snapshot)
- Modified: UrlController (rate-limit responses, new GET /api/v1/urls/{code}/analytics/daily)
- Modified: ShortUrlService.resolveAndRecordClick (redirect hot path: keep 302 contract, keep latency)
- Modified: ClickEventRepository (daily aggregation query); AnalyticsResponse stays backward compatible
- New: RateLimitFilter (per-IP token bucket on POST /api/v1/urls), ClickDaily rollup table
- Data flow: redirect -> ClickEvent insert (unchanged); rollup computed on read from click_events; no write amplification
- Existing schema: V1__create_url_tables.sql defines short_urls and click_events; new migration must be additive
- Risks: 429 behaviour change for bulk clients; index idx_click_events_url_time must cover the daily query