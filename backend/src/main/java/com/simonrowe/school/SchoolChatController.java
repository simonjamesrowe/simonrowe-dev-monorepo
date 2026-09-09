package com.simonrowe.school;

import com.simonrowe.school.chat.SchoolChatResponse;
import com.simonrowe.school.chat.SchoolChatService;
import com.simonrowe.school.model.YearGroups;
import com.simonrowe.school.retrieval.SchoolAudience;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public Term Time endpoint.
 *
 * <p>Unauthenticated by design: the whole point of the public tier is that a parent can ask when
 * half term is without an account. What makes that safe is not this controller but the
 * {@link SchoolAudience} it resolves — anonymous callers get an audience that can only reach
 * public content, and the tier filter and the output-side name check both hang off it.
 *
 * <p><b>The audience is derived from the {@link Authentication} only.</b> There is deliberately no
 * request field, header or query parameter that influences it. A client-supplied tier hint is the
 * obvious convenience and the obvious hole.
 */
@RestController
@RequestMapping("/api/school")
public class SchoolChatController {

  /**
   * The Auth0 role granting the restricted tier.
   *
   * <p>Currently the <b>site admin</b> role, mirroring {@code SecurityConfig.ADMIN_ROLE}. That is
   * a deliberate simplification for a single-user deployment, not the end state: it means the
   * only way to give someone the school tier is to make them a CMS administrator too.
   *
   * <p>Restated here rather than imported because {@code SecurityConfig.ADMIN_ROLE} is
   * package-private, and widening it to share one string would be a worse trade than repeating
   * it with this comment. If a second person ever needs school access, change this back to a
   * dedicated {@code ROLE_SCHOOL} — the rest of the tiering does not care which string it is.
   */
  private static final String SCHOOL_AUTHORITY = "ROLE_DEV_PORTAL_ADMIN";

  private final SchoolChatService chatService;
  private final SchoolProperties properties;

  public SchoolChatController(
      final SchoolChatService chatService, final SchoolProperties properties) {
    this.chatService = chatService;
    this.properties = properties;
  }

  /**
   * Answers a question.
   *
   * @param request the question and selected year group
   * @param authentication the caller's identity, null when anonymous
   * @return the answer
   */
  @PostMapping("/chat")
  public ResponseEntity<SchoolChatResponse> chat(
      @RequestBody final SchoolChatRequest request, final Authentication authentication) {
    if (!properties.enabled()) {
      return ResponseEntity.status(503).body(new SchoolChatResponse(
          "Term Time is not switched on.", SchoolChatResponse.Outcome.UNAVAILABLE));
    }
    final SchoolAudience audience = resolveAudience(authentication);
    final List<String> yearGroups = YearGroups.sanitise(request.yearGroups());
    return ResponseEntity.ok(chatService.answer(request.question(), yearGroups, audience));
  }

  /**
   * The year groups the selector should offer, and whether the caller holds the school role.
   *
   * @param authentication the caller's identity, null when anonymous
   * @return the selector options and the caller's tier
   */
  @GetMapping("/config")
  public SchoolConfigResponse config(final Authentication authentication) {
    return new SchoolConfigResponse(
        YearGroups.ALL, resolveAudience(authentication).authenticated(), properties.enabled());
  }

  /**
   * Maps an authentication to an audience.
   *
   * <p>An authenticated caller without the school role is treated exactly as anonymous, not as an
   * error. Someone signed into the main site who wanders onto this page should get the public
   * assistant, not a 403 they cannot act on.
   */
  private SchoolAudience resolveAudience(final Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return SchoolAudience.anonymous();
    }
    final boolean hasRole = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(SCHOOL_AUTHORITY::equals);
    return hasRole ? SchoolAudience.schoolMember() : SchoolAudience.anonymous();
  }

  /**
   * A question.
   *
   * @param question what was asked
   * @param yearGroups the selected year groups; unrecognised entries are dropped rather than
   *     rejected, so one bad value does not discard a valid selection alongside it
   */
  public record SchoolChatRequest(
      @NotBlank @Size(max = 500) String question,
      List<String> yearGroups) {
  }

  /**
   * What the frontend needs to render itself.
   *
   * @param yearGroups the selector options
   * @param authenticated whether the caller holds the school role
   * @param enabled whether the feature is switched on at all
   */
  public record SchoolConfigResponse(
      List<String> yearGroups, boolean authenticated, boolean enabled) {
  }
}
