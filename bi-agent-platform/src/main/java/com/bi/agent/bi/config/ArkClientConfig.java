package com.bi.agent.bi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * AI 大模型 API 配置（兼容 OpenAI 协议的统一 AI 网关）
 *
 * 直接通过 HTTP REST 调用网关的 Chat Completions / Embeddings 端点
 * 不依赖 OpenAI Java SDK，避免版本兼容问题
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Configuration
public class ArkClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ArkClientConfig.class);

    @Value("${ai.ark.base-url:https://ai-api-prod.qingjiao.art/v1}")
    private String baseUrl;

    @Value("${ai.ark.api-key:${ARK_API_KEY:}}")
    private String apiKey;

    @Value("${ai.ark.model:deepseek-v3}")
    private String model;

    @Value("${ai.ark.embedding-model:BAAI/bge-m3}")
    private String embeddingModel;

    @Value("${ai.ark.timeout-ms:180000}")
    private int timeoutMs;

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * 检查API Key是否已配置
     */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.trim().isEmpty() && !"${ARK_API_KEY:}".equals(apiKey);
    }
}
