package com.simonrowe.meals;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V058CreateMeals;
import com.simonrowe.migration.changeunits.V059CreateMealsKitchen;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/** The kitchen, Up next, shopping and Cooked-it routes against a real Mongo. */
class MealsKitchenApiTest extends AbstractIntegrationTest {

  @Autowired
  private MongoTemplate mongoTemplate;

  @BeforeEach
  void loadTheTestLibrary() throws Exception {
    V058CreateMeals.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    V059CreateMealsKitchen.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    V058CreateMeals.createIndexes(mongoTemplate);
    V059CreateMealsKitchen.createIndexes(mongoTemplate);
    mockMvc.perform(multipart("/api/meals/admin/bundles")
            .file(new MockMultipartFile("file", "b.zip", "application/zip", MealBundles.valid()))
            .with(owner("simon")))
        .andExpect(status().isOk());
  }

  @Test
  void changesUpdateTheKitchenAndItsHistory() throws Exception {
    changes("simon", """
        {"kind":"quick-add","changes":[
          {"name":"Beef Mince","action":"add","quantity":500,"unit":"g","useBy":"2026-10-20"},
          {"name":"oats","action":"status","status":"low"}]}""")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name", contains("beef mince", "oats")))
        .andExpect(jsonPath("$[0].quantity").value(500.0))
        .andExpect(jsonPath("$[0].useBy").value("2026-10-20"))
        .andExpect(jsonPath("$[1].status").value("low"));

    mockMvc.perform(get("/api/meals/kitchen/history").with(owner("simon")))
        .andExpect(jsonPath("$[0].kind").value("quick-add"))
        .andExpect(jsonPath("$[0].summary").value("+ 500g beef mince, oats: low"));
    mockMvc.perform(get("/api/meals/kitchen").with(owner("someone-else")))
        .andExpect(jsonPath("$", hasSize(0)));

    mockMvc.perform(delete("/api/meals/kitchen/oats").with(owner("simon")))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/meals/kitchen").with(owner("simon")))
        .andExpect(jsonPath("$[*].name", contains("beef mince")));
  }

  @Test
  void badChangeRefusesTheWholeBatch() throws Exception {
    changes("simon", """
        {"changes":[{"name":"onion","action":"add","quantity":2,"unit":"each"},
                    {"name":"oats","action":"steal"}]}""")
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/meals/kitchen").with(owner("simon")))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void coverageAndSuggestionsFollowTheKitchen() throws Exception {
    changes("simon", """
        {"changes":[{"name":"beef mince","action":"add","quantity":500,"unit":"g"},
                    {"name":"onion","action":"add","quantity":1,"unit":"each"}]}""");

    mockMvc.perform(get("/api/meals/coverage").with(owner("simon")))
        .andExpect(jsonPath("$['test-chilli'].missing", hasSize(0)))
        .andExpect(jsonPath("$['test-oats'].unknown", contains("oats")));
    mockMvc.perform(get("/api/meals/suggestions").param("slot", "main").with(owner("simon")))
        .andExpect(jsonPath("$[0].recipeId").value("test-chilli"))
        .andExpect(jsonPath("$[0].reasons[0]").value("you have everything"));
    mockMvc.perform(get("/api/meals/suggestions").param("slot", "lunch").with(owner("simon")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cookingRatesTakesFromTheKitchenAndLeavesUpNext() throws Exception {
    changes("simon", """
        {"changes":[{"name":"beef mince","action":"add","quantity":800,"unit":"g"}]}""");
    mockMvc.perform(put("/api/meals/up-next/test-chilli").with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.portions").value(4));
    mockMvc.perform(get("/api/meals/recipes/test-chilli/deductions").param("portions", "4")
            .with(owner("simon")))
        .andExpect(jsonPath("$[0].name").value("beef mince"))
        .andExpect(jsonPath("$[0].amount").value(500.0));

    mockMvc.perform(post("/api/meals/cooked").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"recipeId":"test-chilli","portions":4,"score":5,"again":true,
                 "tags":["filling"],"notes":"more chilli",
                 "deductions":[{"name":"beef mince","tier":"tracked","amount":500,"unit":"g",
                                "ranOut":false},
                               {"name":"onion","tier":"tracked","amount":1,"unit":"each",
                                "ranOut":false}]}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.score").value(5));

    mockMvc.perform(get("/api/meals/kitchen").with(owner("simon")))
        .andExpect(jsonPath("$[0].quantity").value(300.0));
    mockMvc.perform(get("/api/meals/up-next").with(owner("simon")))
        .andExpect(jsonPath("$", hasSize(0)));
    mockMvc.perform(get("/api/meals/kitchen/history").with(owner("simon")))
        .andExpect(jsonPath("$[0].summary").value("cooked Test Chilli"));
  }

  @Test
  void cookWithOutOfRangeAmountChangesNothing() throws Exception {
    changes("simon", """
        {"changes":[{"name":"beef mince","action":"add","quantity":800,"unit":"g"},
                    {"name":"onion","action":"add","quantity":3,"unit":"each"}]}""");

    mockMvc.perform(post("/api/meals/cooked").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"recipeId":"test-chilli","portions":4,"score":4,
                 "deductions":[{"name":"onion","tier":"tracked","amount":1,"unit":"each",
                                "ranOut":false},
                               {"name":"beef mince","tier":"tracked","amount":2000000,
                                "unit":"g","ranOut":false}]}"""))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/meals/kitchen").with(owner("simon")))
        .andExpect(jsonPath("$[?(@.name=='beef mince')].quantity", contains(800.0)))
        .andExpect(jsonPath("$[?(@.name=='onion')].quantity", contains(3.0)));
    mockMvc.perform(get("/api/meals/ratings").with(owner("simon")))
        .andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void weekQueuesItsRecipesAndTheListIsUpNextMinusTheKitchen() throws Exception {
    mockMvc.perform(post("/api/meals/up-next/templates/tp-1500-w1").with(owner("simon")))
        .andExpect(jsonPath("$[*].recipeId", contains("test-chilli", "test-oats")));
    changes("simon", """
        {"changes":[{"name":"beef mince","action":"add","quantity":200,"unit":"g"}]}""");

    mockMvc.perform(get("/api/meals/shopping").with(owner("simon")))
        .andExpect(jsonPath("$.upNextCount").value(2))
        .andExpect(jsonPath("$.items[*].searchTerm",
            contains("beef mince 300g", "onion 1", "oats")))
        .andExpect(jsonPath("$.items[1].line.aisle").value("Other"))
        .andExpect(jsonPath("$.items[2].line.aisle").value("Check the cupboard"));
  }

  @Test
  void basketMarksAndDeliveriesStockTheKitchen() throws Exception {
    mockMvc.perform(put("/api/meals/up-next/test-chilli").with(owner("simon"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"portions\":2}"));
    mockMvc.perform(post("/api/meals/shopping/extras").with(owner("simon"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bin Bags\"}"))
        .andExpect(status().isNoContent());
    basket("beef mince", "Ocado").andExpect(status().isNoContent());
    basket("bin bags", "Deliveroo");
    basket("onion", "Tesco").andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/meals/shopping").with(owner("simon")))
        .andExpect(jsonPath("$.items[*].line.name", contains("beef mince", "onion", "bin bags")))
        .andExpect(jsonPath("$.items[*].basket", contains("Ocado", null, "Deliveroo")));

    mockMvc.perform(post("/api/meals/shopping/delivered").with(owner("simon")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name", contains("beef mince")))
        .andExpect(jsonPath("$[0].quantity").value(250.0));
    mockMvc.perform(get("/api/meals/shopping").with(owner("simon")))
        .andExpect(jsonPath("$.items[*].line.name", contains("onion")))
        .andExpect(jsonPath("$.items[0].basket").doesNotExist());
  }

  @Test
  void recipeCanBeAddedAndBookRecipeKeepsItsBookWhenEdited() throws Exception {
    String body = """
        {"name":"Quick Omelette","category":"breakfast","servings":1,
         "perServing":{"kcal":300,"protein":25,"carbs":2,"fat":20},"macrosEstimated":true,
         "vegetarian":true,
         "ingredients":[{"text":"3 eggs","name":"egg","quantity":3,"unit":"each"}],
         "method":["Whisk.","Cook."],"sourceType":"LINK",
         "sourceUrl":"https://example.com/omelette",
         "newIngredients":[
           {"name":"egg","unit":"each","tier":"tracked","aisle":"Dairy & eggs"}]}""";
    mockMvc.perform(post("/api/meals/recipes").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("quick-omelette"))
        .andExpect(jsonPath("$.source.url").value("https://example.com/omelette"));
    mockMvc.perform(post("/api/meals/recipes").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(jsonPath("$.id").value("quick-omelette-2"));
    assertThat(mongoTemplate.findById("egg", MealIngredient.class).tier()).isEqualTo("tracked");

    mockMvc.perform(put("/api/meals/recipes/test-oats").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("Quick Omelette", "Better Oats")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Better Oats"))
        .andExpect(jsonPath("$.source.type").value("BOOK"))
        .andExpect(jsonPath("$.source.pages", contains(8)));

    mockMvc.perform(post("/api/meals/recipes").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("\"servings\":1", "\"servings\":0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("servings")));
  }

  @Test
  void pastedRecipeIsSavedAsTextWithNoAddress() throws Exception {
    mockMvc.perform(post("/api/meals/recipes").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Pasted Pancakes","category":"breakfast","servings":1,
                 "perServing":{"kcal":465,"protein":46,"carbs":44,"fat":11},
                 "ingredients":[{"text":"40g oats","name":"oats","quantity":40,"unit":"g"}],
                 "method":["Blend.","Cook."],"sourceType":"TEXT",
                 "sourceUrl":"https://example.com/ignored"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.source.type").value("TEXT"))
        .andExpect(jsonPath("$.source.url").doesNotExist());
  }

  @Test
  void generatedPhotoNeedsSignInAndName() throws Exception {
    mockMvc.perform(post("/api/meals/images/generate")
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pancakes\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/meals/images/generate").with(owner("simon"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void recipePhotoIsStoredOnlyWhenItIsAnImage() throws Exception {
    mockMvc.perform(multipart("/api/meals/images")
            .file(new MockMultipartFile("image", "p.png", "image/png", MealBundles.PNG))
            .with(owner("simon")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name", containsString(".png")));
    mockMvc.perform(multipart("/api/meals/images")
            .file(new MockMultipartFile("image", "p.png", "image/png", "<svg/>".getBytes()))
            .with(owner("simon")))
        .andExpect(status().isUnsupportedMediaType());
  }

  private ResultActions changes(final String subject, final String body) throws Exception {
    return mockMvc.perform(post("/api/meals/kitchen/changes").with(owner(subject))
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private ResultActions basket(final String name, final String shop) throws Exception {
    return mockMvc.perform(put("/api/meals/shopping/basket/" + name).with(owner("simon"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"shop\":\"" + shop + "\"}"));
  }

  private static JwtRequestPostProcessor owner(final String subject) {
    return adminJwt().jwt(j -> j.subject(subject));
  }
}
