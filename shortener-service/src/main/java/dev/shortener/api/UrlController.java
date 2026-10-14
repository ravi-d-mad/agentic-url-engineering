package dev.shortener.api;

import dev.shortener.service.ShortUrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import java.net.URI;

@RestController
public class UrlController {
    private final ShortUrlService service;
    public UrlController(ShortUrlService service) { this.service = service; }

    @PostMapping("/api/v1/urls")
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        UrlResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/urls/" + created.code())).body(created);
    }

    @GetMapping("/api/v1/urls/{code}")
    public UrlResponse get(@PathVariable String code) { return service.get(code); }

    @GetMapping("/{code:[A-Za-z0-9]{7}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String destination = service.resolveAndRecordClick(code);
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, destination).build();
    }

    @GetMapping("/api/v1/urls/{code}/analytics")
    public AnalyticsResponse analytics(@PathVariable String code) { return service.analytics(code); }

    @DeleteMapping("/api/v1/urls/{code}")
    public ResponseEntity<Void> deactivate(@PathVariable String code) {
        service.deactivate(code);
        return ResponseEntity.noContent().build();
    }
}
