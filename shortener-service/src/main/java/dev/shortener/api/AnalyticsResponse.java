package dev.shortener.api;

import java.time.Instant;

public record AnalyticsResponse(String code, long clickCount, Instant lastClickAt) {}
