package com.tasfb2b.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuración global de la aplicación.
 *
 * <ul>
 *   <li>{@code @EnableScheduling} activa {@code @Scheduled} en
 *       {@link com.tasfb2b.service.CollapseMonitorService}.</li>
 *   <li>CORS abierto hacia el frontend Next.js en localhost:3000.</li>
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig implements WebMvcConfigurer {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",   // Next.js dev
                        "http://frontend:3000"     // Docker Compose
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
