package com.simonrowe.dataops;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.meals.MealRating;
import com.simonrowe.migration.changeunits.V058CreateMeals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** A restore drops each collection, and Mongock never re-runs V058, so restore re-indexes. */
class MealsRestoreIndexesTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RestoreService restoreService;

  @Test
  void restoreRecreatesTheRatingsIndexes() {
    mongoTemplate.dropCollection(MealRating.COLLECTION);

    restoreService.ensureMealIndexes();

    assertThat(mongoTemplate.indexOps(MealRating.COLLECTION).getIndexInfo())
        .extracting(IndexInfo::getName)
        .contains(V058CreateMeals.RATINGS_BY_OWNER_INDEX, V058CreateMeals.RATINGS_BY_RECIPE_INDEX);
  }
}
