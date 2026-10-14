package dev.shortener.service;

import dev.shortener.api.*;
import dev.shortener.model.ClickEvent;
import dev.shortener.model.ShortUrl;
import dev.shortener.repository.ClickEventRepository;
import dev.shortener.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;

@Service
public class ShortUrlService {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LENGTH = 7;
    private final SecureRandom random = new SecureRandom();
    private final ShortUrlRepository urls;
    private final ClickEventRepository clicks;
    private final String baseUrl;

    public ShortUrlService(ShortUrlRepository urls, ClickEventRepository clicks,
            @Value("${app.public-base-url:http://localhost:8081}") String baseUrl) {
        this.urls = urls;
        this.clicks = clicks;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Transactional
    public UrlResponse create(CreateUrlRequest request) {
        String normalized = validateUrl(request.originalUrl());
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresAt must be in the future");
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            String code = newCode();
            if (urls.findByCode(code).isEmpty()) {
                try {
                    ShortUrl saved = urls.saveAndFlush(new ShortUrl(code, normalized, request.expiresAt()));
                    return toResponse(saved);
                } catch (org.springframework.dao.DataIntegrityViolationException collision) {
                    // Unique constraint is authoritative under concurrency; try a new code.
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate a unique short code");
    }

    @Transactional(readOnly = true)
    public UrlResponse get(String code) {
        return toResponse(findActive(code));
    }

    @Transactional
    public String resolveAndRecordClick(String code) {
        ShortUrl url = findActive(code);
        clicks.save(new ClickEvent(url));
        return url.getOriginalUrl();
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse analytics(String code) {
        ShortUrl url = urls.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found"));
        return new AnalyticsResponse(code, clicks.countByShortUrl_Id(url.getId()),
            clicks.findLastClickAt(url.getId()));
    }

    @Transactional
    public void deactivate(String code) {
        ShortUrl url = urls.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found"));
        url.deactivate();
    }

    private ShortUrl findActive(String code) {
        ShortUrl url = urls.findByCode(code)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found"));
        if (!url.isActive() || url.isExpired(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Short URL is inactive or expired");
        }
        return url;
    }

    private String validateUrl(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null ||
                !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString();
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "originalUrl must be an absolute HTTP or HTTPS URL");
        }
    }

    private String newCode() {
        StringBuilder out = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return out.toString();
    }

    private UrlResponse toResponse(ShortUrl url) {
        return new UrlResponse(url.getCode(), baseUrl + "/" + url.getCode(), url.getOriginalUrl(),
            url.getCreatedAt(), url.getExpiresAt(), url.isActive() ? "ACTIVE" : "INACTIVE");
    }
}
