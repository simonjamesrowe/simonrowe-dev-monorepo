package com.simonrowe.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.simonrowe.common.Image;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

  @Mock
  private ProfileRepository profileRepository;

  @Mock
  private SocialMediaLinkRepository socialMediaLinkRepository;

  @InjectMocks
  private ProfileService profileService;

  @Test
  void getProfileReturnsAssembledResponse() {
    Profile profile = sampleProfile();
    List<SocialMediaLink> socialMediaLinks = List.of(
        sampleSocialMediaLink("s-1", "linkedin", "LinkedIn", "https://linkedin.com", false),
        sampleSocialMediaLink("s-2", "github", "GitHub", "https://github.com", true)
    );
    given(profileRepository.findFirstBy()).willReturn(Optional.of(profile));
    given(socialMediaLinkRepository.findAll()).willReturn(socialMediaLinks);

    ProfileResponse response = profileService.getProfile();

    assertThat(response.name()).isEqualTo("Simon Rowe");
    assertThat(response.firstName()).isEqualTo("Simon");
    assertThat(response.lastName()).isEqualTo("Rowe");
    assertThat(response.socialMediaLinks())
        .extracting(SocialMediaLinkResponse::type)
        .containsExactly("github", "linkedin");
    assertThat(response.socialMediaLinks())
        .extracting(SocialMediaLinkResponse::url)
        .containsExactly("https://github.com", "https://linkedin.com");
  }

  @Test
  void getProfileThrowsNotFoundWhenNoProfileExists() {
    given(profileRepository.findFirstBy()).willReturn(Optional.empty());

    assertThatThrownBy(profileService::getProfile)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException responseException = (ResponseStatusException) ex;
          assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(responseException.getReason()).isEqualTo("No profile found");
        });
  }

  private static Profile sampleProfile() {
    Instant now = Instant.parse("2026-02-21T10:00:00Z");
    Image profileImage = new Image(
        "/uploads/profile.jpg",
        "profile.jpg",
        400,
        400,
        "image/jpeg",
        null
    );
    return new Profile(
        "p-1",
        "Simon Rowe",
        "Simon",
        "Rowe",
        "Engineering Leader",
        "PASSIONATE ABOUT AI NATIVE DEV",
        "I am driven to deliver business value.",
        profileImage,
        profileImage,
        profileImage,
        profileImage,
        "London",
        "+447909083522",
        "simon.rowe@gmail.com",
        "",
        "/api/resume",
        now,
        now
    );
  }

  private static SocialMediaLink sampleSocialMediaLink(
      String id,
      String type,
      String name,
      String link,
      boolean includeOnResume
  ) {
    Instant now = Instant.parse("2026-02-21T10:00:00Z");
    return new SocialMediaLink(
        id,
        type,
        name,
        link,
        includeOnResume,
        now,
        now
    );
  }
}
