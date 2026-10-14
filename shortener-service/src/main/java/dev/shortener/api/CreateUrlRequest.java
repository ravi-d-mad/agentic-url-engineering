package dev.shortener.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateUrlRequest(
    @NotBlank @Size(max = 4096) String originalUrl,
    Instant expiresAt
) {}
