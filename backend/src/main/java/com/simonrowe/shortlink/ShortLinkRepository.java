package com.simonrowe.shortlink;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Access to {@code short_links}. The document id is the slug. */
public interface ShortLinkRepository extends MongoRepository<ShortLink, String> {

  /**
   * The single link for one piece of content, if it has one.
   *
   * @param contentType which collection the content lives in
   * @param contentId the content's id
   * @return the link, or empty
   */
  Optional<ShortLink> findByContentTypeAndContentId(
      ShortLinkContentType contentType, String contentId);

  /**
   * The links for a whole listing in one query.
   *
   * <p>This is what keeps rendering 24 news cards at one extra query rather than 24. It is
   * served by the unique {@code (contentType, contentId)} index.
   *
   * @param contentType which collection the content lives in
   * @param contentIds the ids to resolve
   * @return the links found; ids without a link are simply absent
   */
  List<ShortLink> findByContentTypeAndContentIdIn(
      ShortLinkContentType contentType, Collection<String> contentIds);

  /**
   * Every link of one type, for the admin table's title join.
   *
   * @param contentType which collection the content lives in
   * @return the links, in no particular order
   */
  List<ShortLink> findByContentType(ShortLinkContentType contentType);
}
