package dev.shortener.repository;

import dev.shortener.model.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    long countByShortUrl_Id(Long shortUrlId);

    @Query("select max(c.clickedAt) from ClickEvent c where c.shortUrl.id = :id")
    Instant findLastClickAt(@Param("id") Long id);
}
