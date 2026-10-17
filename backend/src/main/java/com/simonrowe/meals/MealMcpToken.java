package com.simonrowe.meals;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A personal access token for the private meals MCP server. Only the SHA-256 of the token is
 * kept; the token itself is shown once, when it is made.
 *
 * @param id random id
 * @param ownerId Auth0 subject the token acts as
 * @param label what it is for, e.g. "Claude Code on the MacBook"
 * @param tokenHash SHA-256 hex of the token
 * @param hint the token's first characters, so the owner can tell tokens apart
 * @param createdAt when it was made
 * @param lastUsedAt when it was last used, if ever
 * @param revokedAt when it was revoked, if it has been
 */
@Document(collection = MealMcpToken.COLLECTION)
public record MealMcpToken(
    @Id String id,
    String ownerId,
    String label,
    String tokenHash,
    String hint,
    Instant createdAt,
    Instant lastUsedAt,
    Instant revokedAt
) {

  public static final String COLLECTION = "meal_mcp_tokens";
}
