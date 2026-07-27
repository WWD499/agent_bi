package com.bi.agent.bi.service.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bi.agent.bi.config.ArkClientConfig;
import com.bi.agent.cache.MultiLevelCache;
import com.bi.agent.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import org.springframework.web.client.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 大模型调用服务（兼容 OpenAI 协议的统一 AI 网关）
 *
 * 直接通过 HTTP REST 调用网关的 Chat Completions / Embeddings 端点
 * 不依赖 OpenAI Java SDK，避免版本兼容问题
 * 支持 deepseek-v3、qwen 系列及 BAAI/bge-m3 等模型
 *
 * <p>Phase 1 暂未接入 Redis（去掉原 if依 RedisCache 依赖）；缓存能力将在
 * Phase 2（Spring AI + Redis 记忆）一并补齐。当前每次调用直连网关。
 *
 * @author ruoyi-bi (ported to agent-bi)
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    @Autowired
    private ArkClientConfig arkConfig;

    /**
     * 真·多级缓存（L1 Caffeine + L2 Redis 复用）的 LLM 响应命名空间实例。
     * key = sha256(model + systemPrompt + userPrompt + toolsJson)（不区分用户），TTL 30 分钟。
     * 仅缓存 chat / chatWithHistory 最终文本；chatRaw（含 tool_calls）/ streamChat 不进缓存（见 Q2）。
     */
    @Autowired
    private MultiLevelCache<String, String> llmCache;

    /**
     * 调用大模型（同步，NL2SQL场景）
     *
     * @param prompt 用户输入的提示词（作为user message）
     * @return 大模型返回的文本
     */
    public String chat(String prompt) {
        return chat(prompt, 0.1);
    }

    /**
     * 调用大模型（同步）
     *
     * @param prompt       用户输入的提示词
     * @param temperature  温度参数（NL2SQL用0.1，对话用0.3）
     * @return 大模型返回的文本
     */
    public String chat(String prompt, double temperature) {
        checkEnabled();
        if (log.isTraceEnabled()) {
            log.trace("[LLM-chat] 请求 prompt(温度={}):\n{}", temperature, prompt);
        }
        // Q2：key 不区分用户、不纳入 temperature；TTL 30 分钟
        String key = buildCacheKey(arkConfig.getModel(), "", prompt, "");
        return llmCache.get("llm", key, Duration.ofMinutes(30), String.class, k -> {
            try {
                log.debug("调用大模型，prompt长度：{}", prompt.length());

                // 构建请求体
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", arkConfig.getModel());
                requestBody.put("temperature", temperature);
                requestBody.put("max_tokens", 2000);

                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", prompt);
                messages.add(userMsg);
                requestBody.put("messages", messages);

                // 发送HTTP请求
                String response = postForJson(arkConfig.getBaseUrl() + "/chat/completions", requestBody);
                String content = parseResponse(response);
                if (log.isTraceEnabled()) {
                    log.trace("[LLM-chat] 响应内容(长度={}):\n{}", content.length(), content);
                }
                return content;

            } catch (HttpClientErrorException e) {
                log.error("大模型调用HTTP错误，状态码：{}", e.getStatusCode(), e);
                if (e.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                    throw new BizException("API Key无效或已过期，请联系管理员");
                } else if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    throw new BizException("请求过于频繁，请稍后再试");
                } else {
                    throw new BizException("API调用失败（HTTP " + e.getStatusCode() + "）：" + e.getMessage());
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                log.error("大模型调用失败", e);
                throw new BizException("大模型调用失败：" + e.getMessage());
            }
        });
    }

    /**
     * 调用大模型（带上下文，对话场景）
     *
     * @param messages 对话历史，每个元素为 Map 包含 role 和 content
     * @return 大模型返回的文本
     */
    public String chatWithHistory(List<Map<String, String>> messages) {
        return chatWithHistory(messages, 0.3);
    }

    /**
     * 调用大模型（带上下文，对话场景）
     */
    public String chatWithHistory(List<Map<String, String>> messages, double temperature) {
        checkEnabled();
        if (log.isTraceEnabled()) {
            log.trace("[LLM-chatWithHistory] 请求 messages:\n{}", JSON.toJSONString(messages));
        }
        // Q2：key 由 model + 完整对话历史（tools 为空，对话场景不带 function 定义）决定；TTL 30 分钟
        String key = buildCacheKey(arkConfig.getModel(), "", JSON.toJSONString(messages), "");
        return llmCache.get("llm", key, Duration.ofMinutes(30), String.class, k -> {
            try {
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", arkConfig.getModel());
                requestBody.put("temperature", temperature);
                requestBody.put("max_tokens", 2000);
                requestBody.put("messages", messages);

                String response = postForJson(arkConfig.getBaseUrl() + "/chat/completions", requestBody);
                String content = parseResponse(response);
                if (log.isTraceEnabled()) {
                    log.trace("[LLM-chatWithHistory] 响应内容(长度={}):\n{}", content.length(), content);
                }
                return content;

            } catch (Exception e) {
                log.error("大模型调用失败", e);
                throw new BizException("大模型调用失败：" + e.getMessage());
            }
        });
    }

    /**
     * 调用向量化模型（用于RAG）
     *
     * @param texts 待向量化的文本列表
     * @return 向量列表（每个文本对应一个float[]）
     */
    public List<float[]> embed(List<String> texts) {
        checkEnabled();
        if (log.isTraceEnabled()) {
            log.trace("[LLM-embed] 请求 texts({} 条):\n{}", texts.size(), texts);
        }

        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkConfig.getEmbeddingModel());
            requestBody.put("input", texts);

            String response = postForJson(arkConfig.getBaseUrl() + "/embeddings", requestBody);
            List<float[]> vectors = parseEmbedResponse(response);
            if (log.isTraceEnabled()) {
                int dim = vectors.isEmpty() ? 0 : vectors.get(0).length;
                log.trace("[LLM-embed] 响应: {} 个向量, 维度={}", vectors.size(), dim);
            }
            return vectors;

        } catch (Exception e) {
            log.error("向量化调用失败", e);
            throw new BizException("向量化失败：" + e.getMessage());
        }
    }

    /**
     * 发送POST请求，返回响应JSON字符串
     */
    private String postForJson(String url, Map<String, Object> body) {
        // 关键：必须显式设置连接/读取超时，否则 RestTemplate 默认无限等待，
        // 大模型（deepseek-v3 等长文本抽取）响应慢时会永久挂起线程。
        // 超时值取自 ArkClientConfig 的 ai.ark.timeout-ms（默认 180000 = 180s）。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int ms = arkConfig.getTimeoutMs();
        factory.setConnectTimeout(Duration.ofMillis(ms));
        factory.setReadTimeout(Duration.ofMillis(ms));
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(arkConfig.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (response.getStatusCode() != HttpStatus.OK) {
                throw new BizException("AI网关返回错误码：" + response.getStatusCode());
            }
            return response.getBody();
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // 透传HTTP异常，由上层统一处理（区分401/429等）
            throw e;
        } catch (ResourceAccessException e) {
            throw new BizException("网络连接超时，请检查网络后重试");
        } catch (RestClientException e) {
            throw new BizException("AI网关调用失败：" + e.getMessage());
        }
    }

    /**
     * 解析Chat Completions响应，提取content文本
     * 响应格式：{"choices":[{"message":{"content":"..."}}]}
     */
    private String parseResponse(String responseJson) {
        try {
            JSONObject root = JSON.parseObject(responseJson);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new BizException("大模型返回为空（无choices）");
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message.getString("content");
            if (content == null || content.trim().isEmpty()) {
                throw new BizException("大模型返回内容为空");
            }
            return content.trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析大模型返回失败，response={}", responseJson, e);
            throw new BizException("解析大模型返回失败：" + e.getMessage());
        }
    }

    /**
     * 解析Embeddings响应，提取向量
     * 响应格式：{"data":[{"embedding":[0.1,0.2,...]}]}
     */
    private List<float[]> parseEmbedResponse(String responseJson) {
        List<float[]> result = new ArrayList<>();
        try {
            JSONObject root = JSON.parseObject(responseJson);
            JSONArray data = root.getJSONArray("data");
            for (int i = 0; i < data.size(); i++) {
                JSONArray embedding = data.getJSONObject(i).getJSONArray("embedding");
                float[] vec = new float[embedding.size()];
                for (int j = 0; j < embedding.size(); j++) {
                    vec[j] = embedding.getFloatValue(j);
                }
                result.add(vec);
            }
            return result;
        } catch (Exception e) {
            log.error("解析向量化返回失败", e);
            throw new BizException("解析向量化返回失败：" + e.getMessage());
        }
    }

    /**
     * 原生 Chat Completions 调用（支持 tools 函数调用参数），返回网关原始 JSON 响应。
     *
     * <p>供 Agent 的 ReAct 循环解析 {@code tool_calls} 使用：把对话历史
     * （含 role/content，必要时含 assistant 的 tool_calls 与 tool 角色的回传结果）
     * 与 tools JSON 一并发给模型，模型可能返回最终文本，也可能返回需要执行的 tool_calls。
     *
     * @param messages  对话历史（每个元素为 {role, content} 等结构的 Map）
     * @param toolsJson OpenAI 格式的 tools 参数 JSON 字符串（可 null，表示不带工具）
     * @return 网关原始响应 JSON
     */
    public String chatRaw(List<Map<String, Object>> messages, String toolsJson) {
        checkEnabled();
        if (log.isTraceEnabled()) {
            log.trace("[LLM-chatRaw] 请求 messages:\n{}", JSON.toJSONString(messages));
            if (toolsJson != null && !toolsJson.isBlank()) {
                log.trace("[LLM-chatRaw] 请求 tools:\n{}", toolsJson);
            }
        }
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkConfig.getModel());
            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 2000);
            requestBody.put("messages", messages);
            if (toolsJson != null && !toolsJson.isBlank()) {
                requestBody.put("tools", JSON.parseArray(toolsJson));
            }
            String raw = postForJson(arkConfig.getBaseUrl() + "/chat/completions", requestBody);
            if (log.isTraceEnabled()) {
                log.trace("[LLM-chatRaw] 响应原始JSON:\n{}", raw);
            }
            return raw;
        } catch (Exception e) {
            log.error("大模型原生调用失败", e);
            throw new BizException("大模型调用失败：" + e.getMessage());
        }
    }

    /**
     * 流式 Chat Completions（stream=true），逐 token 回调 onToken。
     *
     * <p>供 Agent 最终答案的 SSE 流式推送。手动解析 OpenAI 格式 SSE
     * （{@code data: \{json\}} 行，含 {@code choices[0].delta.content}），
     * 不依赖任何响应式框架，纯 JDK HttpClient 实现。
     *
     * @param messages  对话历史
     * @param onToken 每收到一个增量 token 时回调
     */
    public void streamChat(List<Map<String, Object>> messages, Consumer<String> onToken) {
        checkEnabled();
        if (log.isTraceEnabled()) {
            log.trace("[LLM-streamChat] 请求 messages:\n{}", JSON.toJSONString(messages));
        }
        final boolean traceOn = log.isTraceEnabled();
        final int[] tokenCount = {0};
        final int[] charTotal = {0};
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", arkConfig.getModel());
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 2000);
            requestBody.put("stream", true);
            requestBody.put("messages", messages);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(arkConfig.getBaseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + arkConfig.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody)))
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        JSONObject o = JSON.parseObject(data);
                        JSONArray choices = o.getJSONArray("choices");
                        if (choices != null && !choices.isEmpty()) {
                            JSONObject choice = choices.getJSONObject(0);
                            JSONObject delta = choice.getJSONObject("delta");
                            if (delta != null) {
                                String content = delta.getString("content");
                                if (content != null && !content.isEmpty()) {
                                    if (traceOn) {
                                        tokenCount[0]++;
                                        charTotal[0] += content.length();
                                    }
                                    onToken.accept(content);
                                }
                            }
                        }
                    } catch (Exception ignore) {
                        // 跳过心跳/注释等非 JSON 行
                    }
                }
            }
            if (traceOn) {
                log.trace("[LLM-streamChat] 响应结束: {} 个 token, 共 {} 字符", tokenCount[0], charTotal[0]);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("流式调用失败", e);
            throw new BizException("流式调用失败：" + e.getMessage());
        }
    }

    /**
     * 测试连接
     */
    public boolean testConnection() {
        try {
            checkEnabled();
            String result = chat("你好");
            return result != null && !result.isEmpty();
        } catch (Exception e) {
            log.error("测试连接失败", e);
            return false;
        }
    }

    /**
     * 构造 LLM 缓存 key（Q2）：sha256(model + systemPrompt + userPrompt + toolsJson)，不区分用户。
     * 相同输入必然命中；不同输入（含不同 tools）必然分桶。temperature 不纳入 key（Q2 决策）。
     * 用 "::" 分隔四段，避免 model/prompt 拼接歧义导致的错误碰撞。
     */
    private String buildCacheKey(String model, String systemPrompt, String userPrompt, String toolsJson) {
        String input = (model == null ? "" : model)
                + "::" + (systemPrompt == null ? "" : systemPrompt)
                + "::" + (userPrompt == null ? "" : userPrompt)
                + "::" + (toolsJson == null ? "" : toolsJson);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然可用，此兜底仅防极端环境；不影响缓存正确性语义
            log.warn("SHA-256 不可用，降级用 hashCode 生成 key", e);
            return String.valueOf(input.hashCode());
        }
    }

    private void checkEnabled() {
        if (!arkConfig.isEnabled()) {
            throw new BizException("AI大模型未启用：api-key 未配置，请在 application.yml 中设置 ai.ark.api-key");
        }
    }
}
