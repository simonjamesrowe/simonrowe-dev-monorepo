package com.simonrowe.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank String sessionId,
    @NotBlank @Size(max = 500) String message
) {
}
