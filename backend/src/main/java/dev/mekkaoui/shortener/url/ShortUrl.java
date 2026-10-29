package dev.mekkaoui.shortener.url;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "short_url")
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 16, unique = true)
    private String code;

    @Column(name = "target_url", nullable = false, columnDefinition = "text")
    private String targetUrl;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ShortUrl() {
    }

    public ShortUrl(String code, String targetUrl) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.targetUrl = Objects.requireNonNull(targetUrl, "targetUrl must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
