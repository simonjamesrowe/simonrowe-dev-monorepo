package com.simonrowe.profile;

import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {

  private final ProfileRepository profileRepository;
  private final SocialMediaLinkRepository socialMediaLinkRepository;

  public ProfileService(
      ProfileRepository profileRepository,
      SocialMediaLinkRepository socialMediaLinkRepository
  ) {
    this.profileRepository = profileRepository;
    this.socialMediaLinkRepository = socialMediaLinkRepository;
  }

  public ProfileResponse getProfile() {
    Profile profile = profileRepository.findFirstBy()
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile found"));

    List<SocialMediaLink> socialMediaLinks = socialMediaLinkRepository.findAll().stream()
        .sorted(Comparator.comparing(SocialMediaLink::type,
            Comparator.nullsLast(String::compareToIgnoreCase)))
        .toList();

    return ProfileResponse.fromEntities(profile, socialMediaLinks);
  }
}
