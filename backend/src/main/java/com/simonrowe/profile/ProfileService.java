package com.simonrowe.profile;

import java.util.Comparator;
import java.util.List;
import com.simonrowe.media.MediaImageHydrator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

  private final ProfileRepository profileRepository;
  private final SocialMediaLinkRepository socialMediaLinkRepository;
  private final MediaImageHydrator mediaImageHydrator;

  public ProfileService(
      final ProfileRepository profileRepository,
      final SocialMediaLinkRepository socialMediaLinkRepository,
      final MediaImageHydrator mediaImageHydrator
  ) {
    this.profileRepository = profileRepository;
    this.socialMediaLinkRepository = socialMediaLinkRepository;
    this.mediaImageHydrator = mediaImageHydrator;
  }

  public ProfileResponse getProfile() {
    Profile profile = profileRepository.findFirstBy()
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile found"));

    List<SocialMediaLink> socialMediaLinks = socialMediaLinkRepository.findAll().stream()
        .sorted(Comparator.comparing(SocialMediaLink::type,
            Comparator.nullsLast(String::compareToIgnoreCase)))
        .toList();

    return ProfileResponse.fromEntities(
        profile,
        socialMediaLinks,
        mediaImageHydrator.hydrate(profile.profileImage(), "medium", "large", "small"),
        mediaImageHydrator.hydrate(profile.sidebarImage(), "small", "thumbnail", "medium"),
        mediaImageHydrator.hydrate(profile.backgroundImage(), "large", "medium", "small"),
        mediaImageHydrator.hydrate(profile.mobileBackgroundImage(), "large", "medium", "small")
    );
  }
}
