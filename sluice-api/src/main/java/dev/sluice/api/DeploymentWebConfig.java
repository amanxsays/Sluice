package dev.sluice.api;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Browser origins are deployment settings; database credentials remain on the backend. */
@Configuration
public class DeploymentWebConfig implements WebMvcConfigurer {
    private final String[] origins;

    public DeploymentWebConfig(@Value("${sluice.cors.allowed-origins:}") String allowedOrigins) {
        origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(origin -> !origin.isEmpty())
                .map(origin -> origin.replaceAll("/+$", ""))
                .toArray(String[]::new);
    }

    @Override public void addCorsMappings(CorsRegistry registry) {
        for (String path : new String[] {"/jobs/**", "/tests/**"}) {
            registry.addMapping(path).allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "OPTIONS")
                    .allowedHeaders("Content-Type", "Accept")
                    .allowCredentials(false).maxAge(3600);
        }
    }
}
