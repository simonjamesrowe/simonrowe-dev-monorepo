package com.simonrowe.platform;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link PlatformRelease}, keyed by commit SHA. */
public interface PlatformReleaseRepository extends MongoRepository<PlatformRelease, String> {

  /**
   * The most recent releases, newest first.
   *
   * <p>A default method over {@code findAll(Pageable)} rather than a derived
   * {@code findTopNBy...} query, because the limit is a request parameter and a derived query
   * would hard-code it.
   *
   * @param limit how many to return
   * @return the releases, newest first
   */
  default List<PlatformRelease> findRecent(final int limit) {
    return findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "commitTime")))
        .getContent();
  }

  /**
   * Releases awaiting a summary, oldest commit first.
   *
   * @param limit how many to claim
   * @return pending releases
   */
  default List<PlatformRelease> findPending(final int limit) {
    return findBySummaryStatus(
        ReleaseSummaryStatus.PENDING,
        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "commitTime")));
  }

  /**
   * Releases in a given summary state.
   *
   * @param status the state to match
   * @param pageable paging and sorting
   * @return the matching releases
   */
  List<PlatformRelease> findBySummaryStatus(ReleaseSummaryStatus status, Pageable pageable);
}
