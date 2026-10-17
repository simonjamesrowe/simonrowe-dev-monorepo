package com.simonrowe.meals;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Personal access tokens for the private meals MCP server. An Auth0 token expires within a day,
 * which a line in Claude Code's config cannot follow, so the MCP server takes one of these
 * instead. Only the SHA-256 is stored; the token is shown once, when made.
 */
@Service
public class McpTokenService {

  static final String PREFIX = "meals_";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Duration TOUCH_EVERY = Duration.ofMinutes(10);
  private static final int MAX_LIVE = 10;

  private final MealMcpTokenRepository tokens;
  private final Clock clock;

  public McpTokenService(final MealMcpTokenRepository tokens, final Clock clock) {
    this.tokens = tokens;
    this.clock = clock;
  }

  /**
   * A token just made: the only time its value is ever returned.
   *
   * @param token the token record
   * @param value the token to put in the client's config
   */
  public record Minted(MealMcpToken token, String value) {
  }

  public Minted mint(final String ownerId, final String label) {
    String clean = label == null || label.isBlank() ? "Claude Code" : label.strip();
    if (clean.length() > 80) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Labels are at most 80 characters");
    }
    long live = tokens.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
        .filter(t -> t.revokedAt() == null).count();
    if (live >= MAX_LIVE) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Revoke an old token before making another");
    }
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String value = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    MealMcpToken token = tokens.save(new MealMcpToken(UUID.randomUUID().toString(), ownerId,
        clean, hash(value), value.substring(0, PREFIX.length() + 4), clock.instant(), null,
        null));
    return new Minted(token, value);
  }

  public List<MealMcpToken> list(final String ownerId) {
    return tokens.findByOwnerIdOrderByCreatedAtDesc(ownerId);
  }

  public void revoke(final String ownerId, final String id) {
    MealMcpToken token = tokens.findById(id).filter(t -> t.ownerId().equals(ownerId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such token"));
    if (token.revokedAt() == null) {
      tokens.save(new MealMcpToken(token.id(), token.ownerId(), token.label(), token.tokenHash(),
          token.hint(), token.createdAt(), token.lastUsedAt(), clock.instant()));
    }
  }

  /** The live token this value belongs to, if any, noting its use at most every ten minutes. */
  public Optional<MealMcpToken> authenticate(final String value) {
    if (value == null || !value.startsWith(PREFIX) || value.length() > 200) {
      return Optional.empty();
    }
    Optional<MealMcpToken> found = tokens.findByTokenHash(hash(value))
        .filter(t -> t.revokedAt() == null);
    found.ifPresent(t -> {
      Instant now = clock.instant();
      if (t.lastUsedAt() == null || t.lastUsedAt().plus(TOUCH_EVERY).isBefore(now)) {
        tokens.save(new MealMcpToken(t.id(), t.ownerId(), t.label(), t.tokenHash(), t.hint(),
            t.createdAt(), now, t.revokedAt()));
      }
    });
    return found;
  }

  static String hash(final String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
