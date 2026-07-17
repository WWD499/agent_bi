package com.bi.agent.agent;

import com.bi.agent.vo.PageResult;
import com.bi.agent.vo.SessionSummaryVo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentMemory Redis 持久化联调测试。
 *
 * <p>前置：本地 Redis 可连（127.0.0.1:6379，无密码，见 application.yml）。
 * 跑法：本地起 Redis 后 `mvn -Dtest=AgentMemoryRedisTest test`。
 * 沙箱若无法起 Redis / PostgreSQL，可跳过运行，仅保留测试代码用于本地验证。
 */
@SpringBootTest
public class AgentMemoryRedisTest {

    @Autowired
    private AgentMemory agentMemory;

    private static final String UID = "unit-test-user";
    private static final String SID = "unit-test-session";

    @Test
    public void testAddAndGet() {
        agentMemory.clear(UID, SID);
        Map<String, Object> user = Map.of("role", "user", "content", "你好，帮我分析上季度销售");
        Map<String, Object> assistant = Map.of("role", "assistant", "content", "好的，这是分析结果……");
        agentMemory.add(UID, SID, user, assistant);
        List<Map<String, Object>> got = agentMemory.get(UID, SID);
        assertEquals(2, got.size());
        assertEquals("user", got.get(0).get("role"));
        assertEquals("assistant", got.get(1).get("role"));
    }

    @Test
    public void testListPagination() {
        agentMemory.clearAll(UID);
        for (int i = 0; i < 5; i++) {
            String sid = SID + "-" + i;
            agentMemory.add(UID, sid,
                    Map.of("role", "user", "content", "问题" + i),
                    Map.of("role", "assistant", "content", "回答" + i));
        }
        PageResult<SessionSummaryVo> page = agentMemory.listSessions(UID, 0, 3);
        assertEquals(5, page.getTotal());
        assertEquals(3, page.getList().size());
        // 倒序：第一条应为最后写入的会话
        assertTrue(page.getList().get(0).getSessionId().endsWith("4"),
                "倒序分页首条应为最后活跃的会话");
    }

    @Test
    public void testClearAndClearAll() {
        agentMemory.clearAll(UID);
        agentMemory.add(UID, "a",
                Map.of("role", "user", "content", "q"),
                Map.of("role", "assistant", "content", "a"));
        agentMemory.add(UID, "b",
                Map.of("role", "user", "content", "q"),
                Map.of("role", "assistant", "content", "b"));
        assertEquals(2, agentMemory.listSessions(UID, 0, 20).getTotal());

        agentMemory.clear(UID, "a");
        assertEquals(1, agentMemory.listSessions(UID, 0, 20).getTotal());

        agentMemory.clearAll(UID);
        assertEquals(0, agentMemory.listSessions(UID, 0, 20).getTotal());
    }
}
