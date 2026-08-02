package com.bi.agent.controller.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatReq(@NotBlank String query, @Size(max = 64) String sessionId, Long datasourceId,
                       Boolean allowWrite, Boolean skipConfirm) {
}
