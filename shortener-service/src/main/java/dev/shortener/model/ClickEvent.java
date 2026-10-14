package dev.shortener.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "click_events")
public class ClickEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "short_url_id", nullable = false)
    private ShortUrl shortUrl;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt = Instant.now();

    protected ClickEvent() {}
    public ClickEvent(ShortUrl shortUrl) { this.shortUrl = shortUrl; }
    public Instant getClickedAt() { return clickedAt; }
}
