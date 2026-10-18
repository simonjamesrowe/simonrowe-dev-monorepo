package com.simonrowe.meals;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V058CreateMeals;
import com.simonrowe.migration.changeunits.V059CreateMealsKitchen;
import com.simonrowe.migration.changeunits.V060CreateMealFavourites;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Recipe favourites ("hearts"): per person, idempotent, against a real Mongo. */
class MealFavouritesApiTest extends AbstractIntegrationTest {

  private static final Instant LAST_WEEK = Instant.parse("2026-10-11T09:00:00Z");

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void loadTheTestLibrary() throws Exception {
    V058CreateMeals.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    V059CreateMealsKitchen.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    mongoTemplate.dropCollection(MealFavourite.COLLECTION);
    V058CreateMeals.createIndexes(mongoTemplate);
    V059CreateMealsKitchen.createIndexes(mongoTemplate);
    V060CreateMealFavourites.createIndexes(mongoTemplate);
    mockMvc.perform(multipart("/api/meals/admin/bundles")
            .file(new MockMultipartFile("file", "b.zip", "application/zip", MealBundles.valid()))
            .with(owner("simon")))
        .andExpect(status().isOk());
  }

  @Test
  void heartingListsNewestFirstAndKeepsTheFirstHeartOnReAdd() throws Exception {
    mongoTemplate.insert(new MealFavourite(MealFavourite.idFor("simon", "test-oats"), "simon",
        "test-oats", LAST_WEEK));

    mockMvc.perform(put("/api/meals/favourites/test-chilli").with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("simon|test-chilli"))
        .andExpect(jsonPath("$.ownerId").value("simon"))
        .andExpect(jsonPath("$.recipeId").value("test-chilli"))
        .andExpect(jsonPath("$.addedAt").isNotEmpty());

    mockMvc.perform(put("/api/meals/favourites/test-oats").with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.addedAt").value(LAST_WEEK.toString()));

    mockMvc.perform(get("/api/meals/favourites").with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].recipeId", contains("test-chilli", "test-oats")))
        .andExpect(jsonPath("$[1].addedAt").value(LAST_WEEK.toString()));
    assertThat(mongoTemplate.count(new Query(), MealFavourite.COLLECTION)).isEqualTo(2);
  }

  @Test
  void unheartingIsIdempotent() throws Exception {
    mockMvc.perform(put("/api/meals/favourites/test-chilli").with(owner("simon")))
        .andExpect(status().isOk());

    mockMvc.perform(delete("/api/meals/favourites/test-chilli").with(owner("simon")))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/api/meals/favourites/test-chilli").with(owner("simon")))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/meals/favourites").with(owner("simon")))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void unknownRecipeIsNotFoundAndNothingIsStored() throws Exception {
    mockMvc.perform(put("/api/meals/favourites/no-such-recipe").with(owner("simon")))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/meals/favourites").with(owner("simon")))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void favouritesAreNeverShared() throws Exception {
    mockMvc.perform(put("/api/meals/favourites/test-chilli").with(owner("simon")))
        .andExpect(status().isOk());
    mockMvc.perform(put("/api/meals/favourites/test-oats").with(mealsUser("kate")))
        .andExpect(status().isOk());

    mockMvc.perform(get("/api/meals/favourites").with(owner("simon")))
        .andExpect(jsonPath("$[*].recipeId", contains("test-chilli")));
    mockMvc.perform(get("/api/meals/favourites").with(mealsUser("kate")))
        .andExpect(jsonPath("$[*].recipeId", contains("test-oats")));

    // Un-hearting the same recipe as someone else touches only their own heart.
    mockMvc.perform(delete("/api/meals/favourites/test-chilli").with(mealsUser("kate")))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/meals/favourites").with(owner("simon")))
        .andExpect(jsonPath("$[*].recipeId", contains("test-chilli")));
  }

  @Test
  void signedOutOrUnrelatedUsersAreRefused() throws Exception {
    mockMvc.perform(get("/api/meals/favourites")).andExpect(status().isUnauthorized());
    mockMvc.perform(put("/api/meals/favourites/test-chilli").with(jwt()))
        .andExpect(status().isForbidden());
  }

  @Test
  void v060CreatesTheOwnerNewestFirstIndex() {
    mongoTemplate.dropCollection(MealFavourite.COLLECTION);

    V060CreateMealFavourites.createIndexes(mongoTemplate);

    IndexInfo index = mongoTemplate.indexOps(MealFavourite.COLLECTION).getIndexInfo().stream()
        .filter(i -> V060CreateMealFavourites.OWNER_ADDED_INDEX.equals(i.getName()))
        .findFirst().orElseThrow();
    assertThat(index.getIndexFields()).extracting(f -> f.getKey())
        .containsExactly("ownerId", "addedAt");
  }

  private static JwtRequestPostProcessor owner(final String subject) {
    return adminJwt().jwt(j -> j.subject(subject));
  }

  private static JwtRequestPostProcessor mealsUser(final String subject) {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_MEALS_USER"))
        .jwt(j -> j.subject(subject));
  }
}
