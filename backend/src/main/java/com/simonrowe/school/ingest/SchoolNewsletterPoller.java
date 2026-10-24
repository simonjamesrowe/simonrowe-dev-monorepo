package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.admin.SchoolLinkFetcher;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads every newsletter the school publishes on its parent portal, emailed or not.
 *
 * <p>Until this existed a newsletter reached Term Time only through the link in the school's
 * Friday email. On 25 September 2026 the school published one and sent no email, so it was never
 * read, and nothing anywhere said a week was missing. The portal's list is the school's own
 * record of what it published, so reading it is the way to be sure of having every issue.
 *
 * <p>An issue is fetched through {@link SchoolLinkFetcher}, the same path the email link takes,
 * so it is stored under the same document id, at the same tier and with the same event
 * extraction. A link row is written first, exactly as the email path does, which gives a failed
 * fetch somewhere to be recorded and retried, and lets an administrator decline an issue for good
 * by ignoring its row in the console.
 */
@Component
public class SchoolNewsletterPoller {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolNewsletterPoller.class);

  /**
   * How many of the newest issues each run looks at. The list goes back years, and anything
   * older than the ingest cutoff is skipped anyway, so this only bounds the first run after the
   * cutoff moves.
   */
  static final int MAX_PER_RUN = 8;

  private final SchoolWebsiteCrawler crawler;
  private final SchoolDocumentRepository documents;
  private final SchoolLinkRepository links;
  private final SchoolLinkFetcher linkFetcher;
  private final SchoolProperties properties;

  public SchoolNewsletterPoller(
      final SchoolWebsiteCrawler crawler,
      final SchoolDocumentRepository documents,
      final SchoolLinkRepository links,
      final SchoolLinkFetcher linkFetcher,
      final SchoolProperties properties) {
    this.crawler = crawler;
    this.documents = documents;
    this.links = links;
    this.linkFetcher = linkFetcher;
    this.properties = properties;
  }

  /**
   * Fetches every listed newsletter Term Time does not hold yet.
   *
   * @return how many were fetched
   */
  public int poll() {
    final List<SchoolWebsiteCrawler.PortalNewsletter> listed = crawler.listNewsletters();
    if (listed.isEmpty()) {
      // The list has had an issue on it every week of term for years, so an empty one means
      // the page moved or could not be read, not that the school stopped publishing.
      LOG.warn("Could not read any newsletters from the school's parent portal list");
      return 0;
    }
    int fetched = 0;
    for (SchoolWebsiteCrawler.PortalNewsletter issue
        : listed.stream().limit(MAX_PER_RUN).toList()) {
      if (beforeCutoff(issue) || alreadyHeld(issue)) {
        continue;
      }
      final Optional<String> linkId = linkToFetch(issue);
      if (linkId.isEmpty()) {
        continue;
      }
      try {
        final Optional<SchoolLink> result = linkFetcher.fetch(linkId.get());
        if (result.map(link -> link.status() == SchoolLink.Status.FETCHED).orElse(false)) {
          LOG.info("Fetched school newsletter {} ({}) from the portal list",
              issue.url(), issue.date());
          fetched++;
        }
      } catch (RuntimeException e) {
        LOG.warn("Could not fetch newsletter {}: {}", issue.url(), e.getMessage());
      }
    }
    return fetched;
  }

  private boolean beforeCutoff(final SchoolWebsiteCrawler.PortalNewsletter issue) {
    return properties.ingestFromDate() != null
        && issue.date().isBefore(properties.ingestFromDate());
  }

  private boolean alreadyHeld(final SchoolWebsiteCrawler.PortalNewsletter issue) {
    return documents.findBySourceTypeAndSourceRef(SchoolSourceType.WEBSITE_PAGE, issue.url())
        .isPresent();
  }

  /**
   * The id of the link row to fetch, written first if it is new.
   *
   * <p>Empty when an administrator has ignored the row: that is a decision, and this runs every
   * hour. A row in any other state is fetched again, because the document it should have
   * produced is not there.
   */
  private Optional<String> linkToFetch(final SchoolWebsiteCrawler.PortalNewsletter issue) {
    final String id =
        SchoolIds.documentId(SchoolSourceType.WEBSITE_PAGE, "portal-list|" + issue.url());
    final Optional<SchoolLink> existing = links.findById(id);
    if (existing.map(link -> link.status() == SchoolLink.Status.IGNORED).orElse(false)) {
      return Optional.empty();
    }
    if (existing.isEmpty()) {
      // No source document: nothing mentioned this link, the school's own list did.
      links.save(new SchoolLink(id, null, issue.url(), issue.title(), Instant.now(),
          SchoolLink.Status.PENDING, null, null));
    }
    return Optional.of(id);
  }
}
