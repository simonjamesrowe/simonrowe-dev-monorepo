package com.simonrowe.factory.deploy.persistence;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Spring Data repository for {@link DeployRunRecord}, keyed by Temporal run id. */
public interface DeployRunRepository extends MongoRepository<DeployRunRecord, String> {

  /**
   * The most recent deploys, newest first — how an operator answers "what is deployed, when did it
   * deploy, and did it pass" for a run that has aged out of Temporal's retention window.
   *
   * @return up to 20 records, newest first
   */
  List<DeployRunRecord> findTop20ByOrderByStartedAtDesc();
}
