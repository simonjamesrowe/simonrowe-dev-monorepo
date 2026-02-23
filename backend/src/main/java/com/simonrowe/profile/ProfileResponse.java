package com.simonrowe.profile;

import java.util.List;

public record ProfileResponse(
    String name,
    String firstName,
    String lastName,
    String title,
    String headline,
    String description,
    Image profileImage,
    Image sidebarImage,
    Image backgroundImage,
    Image mobileBackgroundImage,
    String location,
    String phoneNumber,
    String primaryEmail,
    String secondaryEmail,
    String cvUrl,
    List<SocialMediaLinkResponse> socialMediaLinks
) {

  public static ProfileResponse fromEntities(
      Profile profile,
      List<SocialMediaLink> socialMediaLinks
  ) {
    List<SocialMediaLinkResponse> mappedLinks =
        socialMediaLinks == null
            ? List.of()
            : socialMediaLinks.stream()
                .map(SocialMediaLinkResponse::fromEntity)
                .toList();

    return new ProfileResponse(
        profile.name(),
        profile.firstName(),
        profile.lastName(),
        profile.title(),
        profile.headline(),
        profile.description(),
        profile.profileImage(),
        profile.sidebarImage(),
        profile.backgroundImage(),
        profile.mobileBackgroundImage(),
        profile.location(),
        profile.phoneNumber(),
        profile.primaryEmail(),
        profile.secondaryEmail(),
        profile.cvUrl(),
        mappedLinks
    );
  }
}
