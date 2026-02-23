package com.simonrowe.profile;

public record SocialMediaLinkResponse(
    String type,
    String name,
    String url,
    Boolean includeOnResume
) {

  public static SocialMediaLinkResponse fromEntity(SocialMediaLink entity) {
    return new SocialMediaLinkResponse(
        entity.type(),
        entity.name(),
        entity.link(),
        Boolean.TRUE.equals(entity.includeOnResume())
    );
  }
}
