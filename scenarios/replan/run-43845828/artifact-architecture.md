# Architecture (input digest a47266a4)
- Components: LinkController, LinkService, LinkRepository, ClickRecorder, RateLimiter
- API: POST /api/links, GET /{code} (302), GET /api/links/{code}/stats
- Schema: links(code PK, url, created_at, expires_at), click_events(id, code FK, ts)
- Trade-off: synchronous click write now, queue later if p99 redirect latency suffers