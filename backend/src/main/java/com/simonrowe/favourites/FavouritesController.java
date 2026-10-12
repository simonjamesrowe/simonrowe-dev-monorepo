package com.simonrowe.favourites;

import com.simonrowe.playlist.PlaylistContentType;
import com.simonrowe.playlist.PlaylistRecorder;
import com.simonrowe.playlist.PlaylistSource;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Globally shared favourites over aggregated news articles and events. Reads are public;
 * writes ({@code PUT}/{@code DELETE}) require authentication (any valid JWT — not admin-role
 * gated, see {@code SecurityConfig}) but are not scoped to the caller.
 */
@RestController
@RequestMapping("/api/favourites")
public class FavouritesController {

  private final FavouritesService favouritesService;
  private final PlaylistRecorder playlistRecorder;

  public FavouritesController(
      final FavouritesService favouritesService,
      final PlaylistRecorder playlistRecorder
  ) {
    this.favouritesService = favouritesService;
    this.playlistRecorder = playlistRecorder;
  }

  /**
   * Hearts an article or event. A hearted article also joins the playlist: hearting already
   * asks for its summary, and the summary is what the playlist reads aloud.
   */
  @PutMapping("/{type}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void addFavourite(
      @PathVariable final String type,
      @PathVariable final String id
  ) {
    FavouriteType favouriteType = resolveType(type);
    favouritesService.add(favouriteType, id);
    if (favouriteType == FavouriteType.NEWS) {
      playlistRecorder.record(PlaylistContentType.ARTICLE_SUMMARY, id, PlaylistSource.FAVOURITED);
    }
  }

  @DeleteMapping("/{type}/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeFavourite(
      @PathVariable final String type,
      @PathVariable final String id
  ) {
    favouritesService.remove(resolveType(type), id);
  }

  @GetMapping("/{type}/ids")
  public Set<String> getFavouriteIds(@PathVariable final String type) {
    return favouritesService.getIds(resolveType(type));
  }

  @GetMapping("/{type}")
  public Page<?> listFavourites(
      @PathVariable final String type,
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size
  ) {
    final PageRequest pageRequest = PageRequest.of(page, size);
    return switch (resolveType(type)) {
      case NEWS -> favouritesService.getFavouriteArticles(pageRequest);
      case EVENT -> favouritesService.getFavouriteEvents(pageRequest);
    };
  }

  private FavouriteType resolveType(final String type) {
    return FavouriteType.fromPathSegment(type)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Unknown favourite type: " + type));
  }
}
