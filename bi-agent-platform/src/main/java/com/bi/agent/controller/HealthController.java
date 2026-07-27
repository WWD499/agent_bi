package com.bi.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    /** 探活：供负载均衡 / 容器健康检查调用 */
    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", "ok");
        return m;
    }
}
