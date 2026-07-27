package com.bi.agent.agent;

import com.alibaba.fastjson2.JSON;
import com.bi.agent.vo.ChatMessageVo;
import com.bi.agent.vo.PageResult;
import com.bi.agent.vo.SessionDetailVo;
import com.bi.agent.vo.SessionSummaryVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 会话记忆（Redis 持久化版）
 *
 * <p>按 userId + sessionId 维护最近 N 轮（user + assistant）对话历史，以 Redis 承载：
 * <ul>
 *   <li>正文：List（key = {@code bi:agent:memory:{userId}:{sid}}），存 JSON 字符串</li>
 *   <li>索引：ZSet（key = {@code bi:agent:idx:{userId}}），member = sid，score = 最后活跃时间，倒序分页</li>
 *   <li>元信息：Hash（key = {@code bi:agent:meta:{userId}}），field = sid，value = JSON{title,preview,count,createTime,lastActiveTime}</li>
 * </ul>
 * 支持「历史列表分页 / 单个会话详情 / 删除单条 / 清空全部」，刷新不丢、跨进程共享。
 *
 * <p>用户隔离：Redis key 统一加 userId 前缀；userId 仅由 Controller 层传入，不向工具层渗透。
 * 本次不设过期（持久化），清理只靠「清空」。
 */
@Component
public class AgentMemory {

    private static final Logger log = LoggerFactory.getLogger(AgentMemory.class);

    private static final String PREFIX_MEM = "bi:agent:memory:";
    private static final String PREFIX_IDX = "bi:agent:idx:";
    private static final String PREFIX_META = "bi:agent:meta:";

    /** 每轮 = 1 条 user + 1 条 assistant，保留最近 10 轮（20 条） */
    private static final int MAX_TURNS = 10;

    private final StringRedisTemplate stringRedisTemplate;

    public AgentMemory(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ---- key 构造 ----
    private String memKey(String userId, String sid) {
        return PREFIX_MEM + userId + ":" + sid;
    }

    private String idxKey(String userId) {
        return PREFIX_IDX + userId;
    }

    private String metaKey(String userId) {
        return PREFIX_META + userId;
    }

    // ---- 正文读写 ----
    public List<Map<String, Object>> get(String userId, String sid) {
        try {
            List<String> raw = stringRedisTemplate.opsForList().range(memKey(userId, sid), 0, -1);
            if (raw == null || raw.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map<String, Object>> result = new ArrayList<>(raw.size());
            for (String s : raw) {
                Map<String, Object> m = JSON.parseObject(s, Map.class);
                if (m != null) {
                    result.add(m);
                }
            }
            return result;
        } catch (Exception e) {
            log.error("AgentMemory.get 失败 userId={} sid={}", userId, sid, e);
            return Collections.emptyList();
        }
    }

    public void add(String userId, String sid, Map<String, Object> user, Map<String, Object> assistant) {
        try {
            String mKey = memKey(userId, sid);
            stringRedisTemplate.opsForList().rightPush(mKey, JSON.toJSONString(user));
            stringRedisTemplate.opsForList().rightPush(mKey, JSON.toJSONString(assistant));
            // 仅保留最近 MAX_TURNS*2 条
            stringRedisTemplate.opsForList().trim(mKey, -(MAX_TURNS * 2), -1);

            long now = System.currentTimeMillis();
            // 索引：score = 最后活跃时间（倒序分页用）
            stringRedisTemplate.opsForZSet().add(idxKey(userId), sid, now);

            // 元信息：标题 / 预览 / 计数 / 创建时间 / 最后活跃时间
            String mKeyMeta = metaKey(userId);
            String existing = (String) stringRedisTemplate.opsForHash().get(mKeyMeta, sid);
            boolean metaExists = existing != null && !existing.isEmpty();
            Map<String, Object> meta = metaExists ? JSON.parseObject(existing, Map.class) : new HashMap<>();
            if (!metaExists) {
                // 创建时间仅在首次写入，后续不覆盖
                meta.put("createTime", now);
            }
            meta.put("title", buildTitle(user));
            meta.put("preview", buildPreview(assistant));
            Long size = stringRedisTemplate.opsForList().size(mKey);
            meta.put("count", size == null ? 0L : size);
            meta.put("lastActiveTime", now);
            stringRedisTemplate.opsForHash().put(mKeyMeta, sid, JSON.toJSONString(meta));
        } catch (Exception e) {
            log.error("AgentMemory.add 失败 userId={} sid={}", userId, sid, e);
        }
    }

    public void clear(String userId, String sid) {
        try {
            stringRedisTemplate.delete(memKey(userId, sid));
            stringRedisTemplate.opsForZSet().remove(idxKey(userId), sid);
            stringRedisTemplate.opsForHash().delete(metaKey(userId), sid);
        } catch (Exception e) {
            log.error("AgentMemory.clear 失败 userId={} sid={}", userId, sid, e);
        }
    }

    public void clearAll(String userId) {
        try {
            Set<String> sids = stringRedisTemplate.opsForZSet().range(idxKey(userId), 0, -1);
            if (sids != null) {
                for (String sid : sids) {
                    stringRedisTemplate.delete(memKey(userId, sid));
                }
            }
            stringRedisTemplate.delete(idxKey(userId));
            stringRedisTemplate.delete(metaKey(userId));
        } catch (Exception e) {
            log.error("AgentMemory.clearAll 失败 userId={}", userId, e);
        }
    }

    public PageResult<SessionSummaryVo> listSessions(String userId, int page, int size) {
        try {
            String iKey = idxKey(userId);
            Set<String> sids = stringRedisTemplate.opsForZSet()
                    .reverseRange(iKey, (long) page * size, (long) page * size + size - 1);
            Long total = stringRedisTemplate.opsForZSet().size(iKey);
            long totalVal = total == null ? 0L : total;
            List<SessionSummaryVo> list = new ArrayList<>();
            if (sids != null) {
                String mKey = metaKey(userId);
                for (String sid : sids) {
                    String metaStr = (String) stringRedisTemplate.opsForHash().get(mKey, sid);
                    if (metaStr == null || metaStr.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> meta = JSON.parseObject(metaStr, Map.class);
                    if (meta == null) {
                        continue;
                    }
                    SessionSummaryVo vo = new SessionSummaryVo();
                    vo.setSessionId(sid);
                    vo.setTitle(asString(meta.get("title")));
                    vo.setPreview(asString(meta.get("preview")));
                    vo.setCreateTime(asLong(meta.get("createTime")));
                    vo.setLastActiveTime(asLong(meta.get("lastActiveTime")));
                    Object cnt = meta.get("count");
                    vo.setMessageCount(cnt == null ? 0 : ((Number) cnt).intValue());
                    list.add(vo);
                }
            }
            return new PageResult<>(list, totalVal, page, size);
        } catch (Exception e) {
            log.error("AgentMemory.listSessions 失败 userId={}", userId, e);
            return new PageResult<>(Collections.emptyList(), 0L, page, size);
        }
    }

    public SessionDetailVo getSession(String userId, String sid) {
        try {
            List<Map<String, Object>> messages = get(userId, sid);
            List<ChatMessageVo> chatMessages = new ArrayList<>(messages.size());
            for (Map<String, Object> m : messages) {
                ChatMessageVo cm = new ChatMessageVo(asString(m.get("role")), asString(m.get("content")));
                // 回填图表（若有）：select_chart 的 ECharts option 随 assistant 消息一起落库，
                // 使「切走再回来 / 刷新」从服务端历史恢复时图表仍在
                Object charts = m.get("charts");
                if (charts instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> c = (List<Object>) charts;
                    cm.setCharts(c);
                }
                chatMessages.add(cm);
            }
            String title = "";
            String metaStr = (String) stringRedisTemplate.opsForHash().get(metaKey(userId), sid);
            if (metaStr != null && !metaStr.isEmpty()) {
                Map<String, Object> meta = JSON.parseObject(metaStr, Map.class);
                if (meta != null) {
                    title = asString(meta.get("title"));
                }
            }
            return new SessionDetailVo(sid, title, chatMessages);
        } catch (Exception e) {
            log.error("AgentMemory.getSession 失败 userId={} sid={}", userId, sid, e);
            return new SessionDetailVo(sid, "", new ArrayList<>());
        }
    }

    // ---- 辅助 ----
    private String buildTitle(Map<String, Object> user) {
        Object c = user.get("content");
        String text = c == null ? "" : String.valueOf(c);
        text = text.replace("\n", " ").replace("\r", " ").trim();
        if (text.isEmpty()) {
            return "（无标题）";
        }
        if (text.length() > 20) {
            text = text.substring(0, 20) + "…";
        }
        return text;
    }

    private String buildPreview(Map<String, Object> assistant) {
        Object c = assistant.get("content");
        String text = c == null ? "" : String.valueOf(c);
        if (text.length() > 50) {
            text = text.substring(0, 50) + "…";
        }
        return text;
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
