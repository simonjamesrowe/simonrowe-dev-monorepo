package com.simonrowe.meals;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal access tokens for the meals MCP server. Managing them needs the Auth0 session; a
 * personal token itself is accepted only at {@code /api/meals/mcp}.
 */
@RestController
@RequestMapping("/api/meals/tokens")
public class MealsTokenController {

  private final McpTokenService tokens;

  public MealsTokenController(final McpTokenService tokens) {
    this.tokens = tokens;
  }

  /** Tokens without their hashes. */
  @GetMapping
  public List<TokenView> list(@AuthenticationPrincipal final Jwt jwt) {
    return tokens.list(jwt.getSubject()).stream().map(TokenView::of).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MintedView mint(@AuthenticationPrincipal final Jwt jwt, @RequestBody final Label body) {
    McpTokenService.Minted minted = tokens.mint(jwt.getSubject(), body == null ? null
        : body.label());
    return new MintedView(TokenView.of(minted.token()), minted.value());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal final Jwt jwt, @PathVariable final String id) {
    tokens.revoke(jwt.getSubject(), id);
  }

  /**
   * What a new token is for.
   *
   * @param label e.g. "Claude Code on the MacBook"
   */
  public record Label(String label) {
  }

  /**
   * A token as listed: never its hash.
   *
   * @param id id
   * @param label label
   * @param hint first characters
   * @param createdAt made
   * @param lastUsedAt last used
   * @param revokedAt revoked
   */
  public record TokenView(String id, String label, String hint, Instant createdAt,
      Instant lastUsedAt, Instant revokedAt) {

    static TokenView of(final MealMcpToken t) {
      return new TokenView(t.id(), t.label(), t.hint(), t.createdAt(), t.lastUsedAt(),
          t.revokedAt());
    }
  }

  /**
   * A token just made.
   *
   * @param token the listed form
   * @param value the token itself, shown once
   */
  public record MintedView(TokenView token, String value) {
  }
}
