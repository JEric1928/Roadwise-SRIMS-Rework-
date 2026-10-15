package com.roadwise.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final StepUpInterceptor stepUpInterceptor;

    public WebConfig(StepUpInterceptor stepUpInterceptor) {
        this.stepUpInterceptor = stepUpInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadDir = Paths.get("uploads");
        String uploadPath = uploadDir.toFile().getAbsolutePath();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }

    /**
     * Guards the MFA management surface with a recent-authentication requirement.
     *
     * <p>Paths are listed explicitly and deliberately exclude {@code /api/auth/mfa/select}
     * and {@code /api/auth/verify-mfa}: those are the pre-authentication login ceremony,
     * which by definition runs before anyone has a session to be recent about.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(stepUpInterceptor)
                .addPathPatterns(
                        "/api/auth/mfa/factors/**",
                        "/api/auth/mfa/recovery-codes");
    }

    @Bean
    public CorsFilter corsFilter(
            @Value("${frontend.url:http://localhost:63342}") String frontendUrl) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);

        // Centralized CORS policy: only the configured frontend origin may call
        // the API. Override per-environment with the FRONTEND_URL env var.
        // Comma-separated values are supported (e.g. two production domains).
        Arrays.stream(frontendUrl.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .forEach(config::addAllowedOriginPattern);

        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
