package com.simonrowe.playlist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Names one playlist item in a request body.
 *
 * @param contentType what kind of content
 * @param contentId its id
 */
public record PlaylistItemKey(
    @NotNull PlaylistContentType contentType,
    @NotBlank @Size(max = 64) String contentId
) {

  public String itemId() {
    return PlaylistItem.idFor(contentType, contentId);
  }
}
