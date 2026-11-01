package dev.mekkaoui.shortener.url;

import dev.mekkaoui.shortener.analytics.ClickRecorder;
import dev.mekkaoui.shortener.url.dto.CreateShortUrlRequest;
import dev.mekkaoui.shortener.url.dto.ShortUrlResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class ShortUrlController {

    private final ShortUrlService shortUrlService;
    private final ShortenerProperties properties;
    private final ClickRecorder clickRecorder;

    public ShortUrlController(ShortUrlService shortUrlService, ShortenerProperties properties, ClickRecorder clickRecorder) {
        this.shortUrlService = shortUrlService;
        this.properties = properties;
        this.clickRecorder = clickRecorder;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrl entity = shortUrlService.create(request.url());
        String shortUrl = properties.baseUrl() + "/" + entity.getCode();
        ShortUrlResponse response = new ShortUrlResponse(
                entity.getCode(),
                shortUrl,
                entity.getTargetUrl(),
                entity.getCreatedAt()
        );
        return ResponseEntity
                .created(URI.create(shortUrl))
                .body(response);
    }

    @GetMapping("/{code:[0-9A-Za-z]{1,16}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String targetUrl = shortUrlService.findTargetUrl(code);
        clickRecorder.record(code);
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .location(URI.create(targetUrl))
                .build();
    }
}
