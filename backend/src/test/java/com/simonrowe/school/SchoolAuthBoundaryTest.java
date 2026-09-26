package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.school.chat.SchoolChatResponse;
import com.simonrowe.school.chat.SchoolChatService;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.retrieval.SchoolAudience;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Pins FR-022 to FR-024: which tier a request reaches is decided by the JWT alone.
 *
 * <p>Exercises the controller's audience resolution directly rather than through MockMvc. The
 * thing worth pinning is not that the endpoint returns 200 — it is precisely which
 * {@link SchoolAudience} reaches the service, because that object is what the tier filter and the
 * output-side name check both read.
 */
class SchoolAuthBoundaryTest {

  private static final SchoolProperties ENABLED = new SchoolProperties(
      true, null, List.of("kilmorie.lewisham.sch.uk"), List.of(),
      null, null, null, 0, null, null, null, 100, null, null);

  private SchoolAudience audienceFor(final Authentication authentication) {
    final SchoolChatService service = mock(SchoolChatService.class);
    when(service.answer(any(), any(), any()))
        .thenReturn(new SchoolChatResponse("ok", SchoolChatResponse.Outcome.ANSWERED));

    final SchoolChatController controller = new SchoolChatController(service, ENABLED);
    controller.chat(
        new SchoolChatController.SchoolChatRequest("When is half term?", List.of("Year 3")),
        authentication);

    final ArgumentCaptor<SchoolAudience> captor = ArgumentCaptor.forClass(SchoolAudience.class);
    org.mockito.Mockito.verify(service).answer(any(), any(), captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("an anonymous request reaches only public content")
  void anonymousIsPublicOnly() {
    final SchoolAudience audience = audienceFor(null);

    assertThat(audience.visibilities()).containsExactly(Visibility.PUBLIC);
    assertThat(audience.authenticated()).isFalse();
  }

  @Test
  @DisplayName("a request carrying the admin role reaches restricted content")
  void adminRoleReachesRestricted() {
    final Authentication authentication = new TestingAuthenticationToken(
        "simon", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN")));
    authentication.setAuthenticated(true);

    assertThat(audienceFor(authentication).visibilities())
        .contains(Visibility.RESTRICTED);
  }

  @Test
  @DisplayName("an authenticated user without the granting role is treated exactly as anonymous")
  void otherRolesAreTreatedAsAnonymous() {
    // Not 403. Someone signed into the main site who wanders onto /school should get the public
    // assistant, not an error they cannot act on.
    final Authentication authentication = new TestingAuthenticationToken(
        "visitor", "n/a", List.of(new SimpleGrantedAuthority("ROLE_SOMETHING_ELSE")));
    authentication.setAuthenticated(true);

    final SchoolAudience audience = audienceFor(authentication);
    assertThat(audience.visibilities()).containsExactly(Visibility.PUBLIC);
    assertThat(audience.authenticated()).isFalse();
  }

  @Test
  @DisplayName("an unauthenticated token does not grant the tier even if it carries the role")
  void unauthenticatedTokenGrantsNothing() {
    // A token can carry authorities while isAuthenticated() is false. Reading the authorities
    // without checking that flag would grant the restricted tier to a rejected credential.
    final Authentication authentication = new TestingAuthenticationToken(
        "attacker", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN")));
    authentication.setAuthenticated(false);

    assertThat(audienceFor(authentication).visibilities())
        .containsExactly(Visibility.PUBLIC);
  }

  @Test
  @DisplayName("unrecognised year groups are dropped rather than passed through")
  void invalidYearGroupIsDropped() {
    final SchoolChatService service = mock(SchoolChatService.class);
    when(service.answer(any(), any(), any()))
        .thenReturn(new SchoolChatResponse("ok", SchoolChatResponse.Outcome.ANSWERED));
    new SchoolChatController(service, ENABLED).chat(
        new SchoolChatController.SchoolChatRequest("q", List.of("'; DROP TABLE --")), null);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(service).answer(any(), captor.capture(), any());
    assertThat(captor.getValue()).isEmpty();
  }

  @Test
  @DisplayName("the endpoint reports 503 rather than answering when the feature is off")
  void disabledReturns503() {
    final SchoolProperties disabled = new SchoolProperties(
        false, null, null, null, null, null, null, 0, null, null, null, 0, null, null);
    final SchoolChatService service = mock(SchoolChatService.class);

    final var response = new SchoolChatController(service, disabled).chat(
        new SchoolChatController.SchoolChatRequest("q", null), null);

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    org.mockito.Mockito.verifyNoInteractions(service);
  }
}
