package com.simonrowe.playlist;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The site's shared playlist of summaries and posts to listen to.
 *
 * <p>{@code GET} is public: a signed-out visitor sees the list and can play anything on it.
 * Every write needs a session (any valid JWT, see {@code SecurityConfig}), exactly as for
 * favourites, because the list is shared and "make audio" spends the text-to-speech budget.
 */
@RestController
@RequestMapping("/api/playlist")
@Validated
public class PlaylistController {

  private final PlaylistService playlistService;
  private final PlaylistQueryService queryService;

  public PlaylistController(
      final PlaylistService playlistService,
      final PlaylistQueryService queryService
  ) {
    this.playlistService = playlistService;
    this.queryService = queryService;
  }

  @GetMapping
  public PlaylistResponse playlist() {
    return queryService.playlist();
  }

  @PutMapping("/items/{type}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void add(@PathVariable final String type, @PathVariable final String id) {
    playlistService.add(resolveType(type), id);
  }

  @DeleteMapping("/items/{type}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable final String type, @PathVariable final String id) {
    playlistService.remove(resolveType(type), id);
  }

  @PutMapping("/items/{type}/{id}/progress")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void saveProgress(
      @PathVariable final String type,
      @PathVariable final String id,
      @Valid @RequestBody final PlaylistRequests.Progress progress
  ) {
    playlistService.saveProgress(resolveType(type), id, progress.positionSeconds());
  }

  @PostMapping("/items/{type}/{id}/listened")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markListened(@PathVariable final String type, @PathVariable final String id) {
    playlistService.markListened(resolveType(type), id);
  }

  @PostMapping("/items/{type}/{id}/play-next")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void playNext(@PathVariable final String type, @PathVariable final String id) {
    playlistService.playNext(resolveType(type), id);
  }

  @DeleteMapping("/listened")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearListened() {
    playlistService.clearListened();
  }

  @PostMapping("/narrations")
  public PlaylistRequests.AudioResults requestAudio(
      @Valid @RequestBody final PlaylistRequests.Audio request
  ) {
    return new PlaylistRequests.AudioResults(playlistService.requestAudio(request.items()));
  }

  @PutMapping("/settings")
  public PlaylistRequests.Settings updateSettings(
      @RequestBody final PlaylistRequests.Settings settings
  ) {
    return new PlaylistRequests.Settings(
        playlistService.updateSettings(settings.autoVoice()).autoVoice());
  }

  private static PlaylistContentType resolveType(final String type) {
    return PlaylistContentType.fromPathSegment(type)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Unknown playlist item type: " + type));
  }
}
