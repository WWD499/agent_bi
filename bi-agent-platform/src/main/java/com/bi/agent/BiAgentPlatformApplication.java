package com.bi.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.bi.agent.bi.mapper")
public class BiAgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(BiAgentPlatformApplication.class, args);
    }
}
