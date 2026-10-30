package dev.mekkaoui.shortener.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CacheConfiguration {

    @Bean
    public UrlCache urlCache() {
        return new NoOpUrlCache();
    }
}
