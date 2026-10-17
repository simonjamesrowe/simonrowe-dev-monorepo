package com.simonrowe.meals;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.migration.changeunits.V058CreateMeals;
import com.simonrowe.migration.changeunits.V059CreateMealsKitchen;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The private meals MCP server end to end, over real HTTP: the personal token is the only way
 * in, the tools act as the token's owner, and none of them leak onto the public {@code /mcp}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MealsMcpServerTest extends AbstractIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort
  private int port;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ApplicationContext context;

  @Autowired
  private MealsMcpTools tools;

  @BeforeEach
  void emptyMeals() {
    V058CreateMeals.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    V059CreateMealsKitchen.COLLECTIONS.forEach(mongoTemplate::dropCollection);
    V058CreateMeals.createIndexes(mongoTemplate);
    V059CreateMealsKitchen.createIndexes(mongoTemplate);
  }

  @Test
  void tokenIsShownOnceListedWithoutItsHashAndRevocable() throws Exception {
    String token = mint();

    assertThat(token).startsWith("meals_");
    mockMvc.perform(get("/api/meals/tokens").with(adminJwt().jwt(j -> j.subject("simon"))))
        .andExpect(jsonPath("$[0].label").value("Claude Code"))
        .andExpect(jsonPath("$[0].tokenHash").doesNotExist())
        .andExpect(jsonPath("$[0].hint").value(token.substring(0, 10)));
    assertThat(rpc(token, "tools/list", "{}").statusCode()).isEqualTo(200);

    String id = mongoTemplate.findAll(MealMcpToken.class).getFirst().id();
    mockMvc.perform(delete("/api/meals/tokens/" + id).with(adminJwt().jwt(j -> j.subject("x"))))
        .andExpect(status().isNotFound());
    mockMvc.perform(delete("/api/meals/tokens/" + id)
            .with(adminJwt().jwt(j -> j.subject("simon"))))
        .andExpect(status().isNoContent());
    assertThat(rpc(token, "tools/list", "{}").statusCode()).isEqualTo(401);
  }

  @Test
  void onlyMealsTokensGetIn() throws Exception {
    assertThat(rpc(null, "tools/list", "{}").statusCode()).isEqualTo(401);
    assertThat(rpc("meals_not-a-real-token", "tools/list", "{}").statusCode()).isEqualTo(401);
    assertThat(rpc("eyJhbGciOiJSUzI1NiJ9.e30.sig", "tools/list", "{}").statusCode())
        .isEqualTo(401);
  }

  @Test
  void toolsAreListedAndActAsTheTokensOwner() throws Exception {
    String token = mint();

    JsonNode list = body(rpc(token, "tools/list", "{}"));
    List<String> names = list.path("result").path("tools").valueStream()
        .map(t -> t.path("name").asString()).toList();
    assertThat(names).contains("search_recipes", "suggest_meals", "what_can_i_cook_now",
        "update_inventory", "plan_meal", "get_shopping_list", "record_cooked",
        "import_recipe_from_url");

    JsonNode updated = body(rpc(token, "tools/call", """
        {"name":"update_inventory","arguments":{"changes":[
          {"name":"beef mince","action":"add","quantity":500,"unit":"g"}]}}"""));
    assertThat(updated.path("result").path("isError").asBoolean()).isFalse();
    assertThat(updated.path("result").path("content").get(0).path("text").asString())
        .contains("beef mince").contains("500g");

    mockMvc.perform(get("/api/meals/kitchen").with(adminJwt().jwt(j -> j.subject("simon"))))
        .andExpect(jsonPath("$[0].name").value("beef mince"));
    mockMvc.perform(get("/api/meals/kitchen").with(adminJwt().jwt(j -> j.subject("other"))))
        .andExpect(jsonPath("$").isEmpty());

    JsonNode bad = body(rpc(token, "tools/call", """
        {"name":"get_recipe","arguments":{"id":"no-such-recipe"}}"""));
    assertThat(bad.path("result").path("isError").asBoolean()).isTrue();
  }

  @Test
  void promptsAreOffered() throws Exception {
    JsonNode prompts = body(rpc(mint(), "prompts/list", "{}"));
    assertThat(prompts.path("result").path("prompts").valueStream()
        .map(p -> p.path("name").asString()).toList())
        .containsExactlyInAnyOrder("plan-my-week", "use-up-whats-expiring");
  }

  @Test
  void noMealsToolIsPublishedOnThePublicMcpServer() {
    List<String> mealsTools = tools.tools().stream().map(MealsMcpTools.ToolDef::name).toList();
    List<String> published = context.getBeansOfType(ToolCallbackProvider.class).values().stream()
        .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
        .map(ToolCallback::getToolDefinition)
        .map(d -> d.name())
        .toList();

    assertThat(published).isNotEmpty().doesNotContainAnyElementsOf(mealsTools);
    assertThat(context.getBeansOfType(ToolCallbackProvider.class).values())
        .noneMatch(p -> p.getClass().getPackageName().startsWith("com.simonrowe.meals"));
  }

  private String mint() throws Exception {
    String response = mockMvc.perform(post("/api/meals/tokens")
            .with(adminJwt().jwt(j -> j.subject("simon")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Claude Code\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return JSON.readTree(response).path("value").asString();
  }

  private HttpResponse<String> rpc(final String token, final String method, final String params)
      throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + MealsMcpSecurity.PATH))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString("""
            {"jsonrpc":"2.0","id":1,"method":"%s","params":%s}""".formatted(method, params)));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode body(final HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return JSON.readTree(response.body());
  }
}
