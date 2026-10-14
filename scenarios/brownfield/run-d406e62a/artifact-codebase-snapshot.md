# Codebase snapshot: shortener-service/src/main/java

## dev/shortener/UrlShortenerApplication.java
- class UrlShortenerApplication

## dev/shortener/api/AnalyticsResponse.java
- record AnalyticsResponse

## dev/shortener/api/CreateUrlRequest.java
- record CreateUrlRequest

## dev/shortener/api/UrlController.java
- class UrlController
- endpoint @PostMapping("/api/v1/urls")
- endpoint @GetMapping("/api/v1/urls/{code}")
- endpoint @GetMapping("/{code:[A-Za-z0-9]{7}}")
- endpoint @GetMapping("/api/v1/urls/{code}/analytics")
- endpoint @DeleteMapping("/api/v1/urls/{code}")

## dev/shortener/api/UrlResponse.java
- record UrlResponse

## dev/shortener/model/ClickEvent.java
- class ClickEvent
- JPA entity

## dev/shortener/model/ShortUrl.java
- class ShortUrl
- JPA entity

## dev/shortener/repository/ClickEventRepository.java
- interface ClickEventRepository

## dev/shortener/repository/ShortUrlRepository.java
- interface ShortUrlRepository

## dev/shortener/service/ShortUrlService.java
- class ShortUrlService

Files scanned: 10
