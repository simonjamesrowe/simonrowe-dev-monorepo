package com.simonrowe.narration;

import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NarrationRepository extends MongoRepository<Narration, String> {

  List<Narration> findByBlogId(String blogId);

  List<Narration> findByStatusAndUpdatedAtBefore(
      NarrationStatus status, Instant updatedBefore);

  List<Narration> findByStatusAndLeaseUntilBefore(
      NarrationStatus status, Instant leaseBefore);

  List<Narration> findByProviderRequestStartedTrueAndRequestedAtBetween(
      Instant from, Instant to);
}
