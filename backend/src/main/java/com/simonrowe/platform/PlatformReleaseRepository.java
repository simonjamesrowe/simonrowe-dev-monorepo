package com.simonrowe.platform;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link PlatformRelease}, keyed by commit SHA. */
public interface PlatformReleaseRepository extends MongoRepository<PlatformRelease, String> {

  /**
   * Releases awaiting a summary, newest commit first.
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
