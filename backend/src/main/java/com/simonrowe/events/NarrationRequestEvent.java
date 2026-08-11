package com.simonrowe.events;

import java.time.Instant;

public record NarrationRequestEvent(String narrationId, Instant requestedAt) {
}
