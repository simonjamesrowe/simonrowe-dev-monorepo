package com.simonrowe.factory.git;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * A GitHub pull request's web address, taken apart.
 *
 * <p>Parsed with {@link URI} and a split rather than a pattern, and by origin rather than prefix:
 * {@code https://github.com.example/...} names some other host, and a string prefix check would
 * read it as GitHub.
 *
 * @param owner the repository owner
 * @param repository the repository name
 * @param number the pull request number
 */
public record PullRequestUrl(String owner, String repository, int number) {

  private static final String HOST = "github.com";

  /**
   * Parses {@code https://github.com/<owner>/<repository>/pull/<number>}.
   *
   * @param url any URL, possibly null
   * @return the parts, or empty when the URL is not a GitHub pull request
   */
  public static Optional<PullRequestUrl> parse(final String url) {
    if (url == null || url.isBlank()) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(url.trim());
    } catch (URISyntaxException exception) {
      return Optional.empty();
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || !HOST.equalsIgnoreCase(uri.getHost())
        || uri.getPort() != -1
        || uri.getPath() == null) {
      return Optional.empty();
    }
    String[] parts = uri.getPath().split("/");
    // "", owner, repository, "pull", number
    if (parts.length != 5 || !parts[0].isEmpty() || !"pull".equals(parts[3])
        || parts[1].isEmpty() || parts[2].isEmpty()) {
      return Optional.empty();
    }
    try {
      int number = Integer.parseInt(parts[4]);
      return number > 0
          ? Optional.of(new PullRequestUrl(parts[1], parts[2], number))
          : Optional.empty();
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  /**
   * The canonical form, for comparing two URLs that name the same pull request.
   *
   * @return {@code https://github.com/<owner>/<repository>/pull/<number>}
   */
  public String canonical() {
    return "https://" + HOST + "/" + owner + "/" + repository + "/pull/" + number;
  }
}
