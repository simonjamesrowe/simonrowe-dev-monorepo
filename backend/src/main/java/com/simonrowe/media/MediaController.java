package com.simonrowe.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/media")
@Validated
public class MediaController {

  private static final Logger LOG =
      LoggerFactory.getLogger(MediaController.class);

  private final MediaService mediaService;

  public MediaController(final MediaService mediaService) {
    this.mediaService = mediaService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public MediaAsset upload(
      @RequestParam("file") final MultipartFile file,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    MediaAsset saved = mediaService.upload(file);
    LOG.info("Uploaded media: id={}, fileName={}, user={}",
        saved.id(), saved.fileName(), jwt.getSubject());
    return saved;
  }

  @GetMapping
  public Page<MediaAsset> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "24") final int size,
      @RequestParam(required = false) final String mimeType,
      @RequestParam(required = false) final String search
  ) {
    return mediaService.list(search, mimeType,
        PageRequest.of(page, size));
  }

  @GetMapping("/{id}")
  public MediaAsset getById(@PathVariable final String id) {
    return mediaService.getById(id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    mediaService.delete(id);
    LOG.info("Deleted media: id={}, user={}", id, jwt.getSubject());
  }
}
