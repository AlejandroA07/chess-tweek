package com.chesstweek.composition.web;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Keeps the browser on the current build. The application page is never stored, because every
 * packaged file carries the same fixed modification date and a cached page would otherwise be
 * reused indefinitely after an update. Built assets have content hashes in their names, so they
 * can be cached permanently.
 */
@Configuration(proxyBeanMethods = false)
class StaticResourceCaching implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore())
                .setUseLastModified(false);
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .setUseLastModified(false);
    }
}
