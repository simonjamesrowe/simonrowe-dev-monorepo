package com.simonrowe.common;

import java.time.Instant;

public record ErrorResponseDto(
    String message,
    int status,
    Instant timestamp
) {
}
