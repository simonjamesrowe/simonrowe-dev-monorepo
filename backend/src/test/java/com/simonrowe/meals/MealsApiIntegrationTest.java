package com.simonrowe.meals;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V058CreateMeals;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

class MealsApiIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void emptyTheLibrary() {
    V058CreateMeals.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    V058CreateMeals.createIndexes(mongoTemplate);
  }

  @Test
  void everyMealsRouteIsForTheAdminAlone() throws Exception {
    mockMvc.perform(get("/api/meals/recipes")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/meals/images/" + "a".repeat(64) + ".jpg"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/meals/recipes").with(jwt().jwt(j -> j.subject("someone"))))
        .andExpect(status().isForbidden());
    mockMvc.perform(multipart("/api/meals/admin/bundles").file(bundle(MealBundles.valid()))
        .with(jwt().jwt(j -> j.subject("someone")))).andExpect(status().isForbidden());
  }

  @Test
  void bundleFillsTheLibraryAndNamesWhatItSkipped() throws Exception {
    importBundle(MealBundles.valid())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recipesAdded").value(2))
        .andExpect(jsonPath("$.ingredientsAdded").value(3))
        .andExpect(jsonPath("$.templatesAdded").value(1))
        .andExpect(jsonPath("$.photosStored").value(1))
        .andExpect(jsonPath("$.skipped", hasSize(1)))
        .andExpect(jsonPath("$.skipped[0]", containsString("Bad Id!")));

    mockMvc.perform(get("/api/meals/recipes").with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name", contains("Test Chilli", "Test Oats")))
        .andExpect(jsonPath("$[0].source.type").value("BOOK"))
        .andExpect(jsonPath("$[0].source.pages", contains(4, 5)))
        .andExpect(jsonPath("$[0].ingredients[2].quantity").doesNotExist())
        .andExpect(jsonPath("$[0].perServing.protein").value(40.0));
    mockMvc.perform(get("/api/meals/ingredients").with(owner("simon")))
        .andExpect(jsonPath("$[*].name", contains("beef mince", "oats", "salt")))
        .andExpect(jsonPath("$[2].assumed").value(true));
    mockMvc.perform(get("/api/meals/templates").with(owner("simon")))
        .andExpect(jsonPath("$[0].recipeIds", contains("test-chilli", "test-oats")));
  }

  @Test
  void photoIsServedPrivatelyWithItsSniffedType() throws Exception {
    importBundle(MealBundles.valid());
    String photo = mongoTemplate.findById("test-chilli", MealRecipe.class).photo();
    assertThat(photo).matches("[0-9a-f]{64}\\.png");

    mockMvc.perform(get("/api/meals/images/" + photo).with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG))
        .andExpect(content().bytes(MealBundles.PNG))
        .andExpect(header().string("Cache-Control", containsString("private")))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    mockMvc.perform(get("/api/meals/images/../../etc.jpg").with(owner("simon")))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void reimportingAddsNothingAndKeepsEdits() throws Exception {
    importBundle(MealBundles.valid());
    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is("test-oats")),
        Update.update("name", "Edited Oats"), MealRecipe.class);

    importBundle(MealBundles.valid())
        .andExpect(jsonPath("$.recipesAdded").value(0))
        .andExpect(jsonPath("$.recipesExisting").value(2))
        .andExpect(jsonPath("$.ingredientsAdded").value(0))
        .andExpect(jsonPath("$.templatesAdded").value(0));
    assertThat(mongoTemplate.findById("test-oats", MealRecipe.class).name())
        .isEqualTo("Edited Oats");
  }

  @Test
  void brokenUploadIsRefusedWithReason() throws Exception {
    importBundle("not a zip".getBytes(StandardCharsets.UTF_8))
        .andExpect(status().isBadRequest());
    importBundle(MealBundles.zip(Map.of("photos/x.png", MealBundles.PNG)))
        .andExpect(status().isBadRequest());
    importBundle(MealBundles.zip(Map.of("manifest.json",
        "{\"version\": 2}".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void ratingsBelongToWhoeverMadeThem() throws Exception {
    importBundle(MealBundles.valid());
    mockMvc.perform(post("/api/meals/ratings").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"recipeId": "test-chilli", "score": 4, "again": true,
                 "tags": ["filling"], "notes": "  more chilli  "}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.notes").value("more chilli"));

    mockMvc.perform(get("/api/meals/ratings").with(owner("simon")))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].score").value(4));
    mockMvc.perform(get("/api/meals/ratings").with(owner("someone-else")))
        .andExpect(jsonPath("$", hasSize(0)));

    String id = mongoTemplate.findAll(MealRating.class).getFirst().id();
    mockMvc.perform(delete("/api/meals/ratings/" + id).with(owner("someone-else")))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/meals/ratings/" + id).with(owner("simon")))
        .andExpect(status().isNoContent());
  }

  @Test
  void ratingMustBeOneToFiveForRealRecipe() throws Exception {
    importBundle(MealBundles.valid());
    mockMvc.perform(post("/api/meals/ratings").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipeId\": \"test-chilli\", \"score\": 6}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/meals/ratings").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipeId\": \"no-such-recipe\", \"score\": 3}"))
        .andExpect(status().isNotFound());
  }

  private ResultActions importBundle(final byte[] bytes) throws Exception {
    return mockMvc.perform(multipart("/api/meals/admin/bundles").file(bundle(bytes))
        .with(owner("simon")));
  }

  private static MockMultipartFile bundle(final byte[] bytes) {
    return new MockMultipartFile("file", "bundle.zip", "application/zip", bytes);
  }

  private static JwtRequestPostProcessor owner(final String subject) {
    return adminJwt().jwt(j -> j.subject(subject));
  }
}
