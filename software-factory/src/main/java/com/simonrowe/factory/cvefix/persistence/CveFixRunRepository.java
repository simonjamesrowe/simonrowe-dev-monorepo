package com.simonrowe.factory.cvefix.persistence;

import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data repository for {@link CveFixRunRecord}, keyed by run id.
 *
 * <p>One row per nightly run is what makes any of this "most recent run" logic meaningful in the
 * first place: {@link CveFixRunRecord#idFor} is the identity function on the Temporal workflow id,
 * and the nightly schedule ({@code CveFixScheduleInitializer.WORKFLOW_ID}) uses a
 * <strong>fixed</strong> workflow id. That id would collide across every nightly firing were it
 * not for Temporal itself appending the nominal scheduled time to a scheduled action's workflow
 * id, which is what gives each night's run a distinct id, and therefore a distinct Mongo row here.
 */
public interface CveFixRunRepository extends MongoRepository<CveFixRunRecord, String> {

  /**
   * The most recently started run other than the one named, among runs that actually observed
   * Dependency-Track.
   *
   * <p>The exclusion matters on a Temporal re-drive: records are written at the end of a run, so
   * the newest row is normally the previous run — but a re-driven workflow id may already have
   * written its own row, and reading it back would compare the run against itself.
   *
   * <p>The status filter matters separately: a run that never reached {@code fetchFindings} (for
   * example because Dependency-Track's shared Postgres was down) is recorded {@code FAILED} with
   * {@code findingsSeen == 0}, which looks identical to a genuinely clean scan. Without this
   * filter, one operational blip between a dirty run and a clean one permanently hides the
   * dirty-to-clean transition: the failed row becomes "the previous run" forever afterwards, since
   * every subsequent clean run also has {@code findingsSeen == 0} and so never displaces it.
   * Restricting to {@link CveFixStatus#COMPLETED} and {@link CveFixStatus#NO_FINDINGS} — the only
   * statuses reached after {@code fetchFindings} returns — means a failure at any point is skipped
   * entirely and the search instead falls back to the last run that actually saw Dependency-Track,
   * which is correct whether that run was dirty or clean.
   *
   * <p>Served by the {@code startedAt} descending index created by
   * {@link CveFixIndexInitializer}.
   *
   * @param id the run record id to exclude, which is the workflow id
   * @param statuses the statuses eligible to be considered "the previous run"
   * @return the previous qualifying run, or empty when there is none
   */
  Optional<CveFixRunRecord> findFirstByIdNotAndStatusInOrderByStartedAtDesc(
      String id, Collection<CveFixStatus> statuses);
}
