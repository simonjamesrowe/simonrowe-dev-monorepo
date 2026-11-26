package com.simonrowe.school.judge;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Persistence for {@link SchoolJudgement}.
 *
 * <p>No index beyond {@code _id}: about twenty school emails arrive a week, so a sort over the
 * whole collection stays trivially cheap for years.
 */
public interface SchoolJudgementRepository extends MongoRepository<SchoolJudgement, String> {

  /**
   * The most recent judgements, newest first.
   *
   * @return at most 200 judgements
   */
  List<SchoolJudgement> findTop200ByOrderByJudgedAtDesc();
}
