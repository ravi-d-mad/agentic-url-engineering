package dev.shortener.api;

import java.time.Instant;

public record UrlResponse(
    String code, String shortUrl, String originalUrl,
    Instant createdAt, Instant expiresAt, String status
) {}
