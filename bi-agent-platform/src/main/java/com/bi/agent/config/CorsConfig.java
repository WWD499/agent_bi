package com.bi.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 配置：显式声明前端开发源（不写 "*"）。
 * Phase 3 前端（agent-ui，Vite 默认 5173）联调时使用。
 * 生产环境应改为正式域名。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许的前端源，逗号分隔。生产环境在 application.yml 中改为正式域名。
     * 默认值保留本地开发用的两个源（Vite 默认 5173）。
     */
    @Value("#{'${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}'.split(',')}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
