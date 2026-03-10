package com.fun.ai.claw.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String allowedOrigins;
    private final OpenApiProperties openApiProperties;

    public WebConfig(@org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins,
                     OpenApiProperties openApiProperties) {
        this.allowedOrigins = allowedOrigins;
        this.openApiProperties = openApiProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addMapping("/v1/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);

        String[] openOrigins = openApiProperties.getAllowedOrigins().split(",");
        registry.addMapping("/open/v1/**")
                .allowedOriginPatterns(openOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

