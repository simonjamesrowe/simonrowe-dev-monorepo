package com.simonrowe.homepage;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class HomePageControllerTest extends AbstractIntegrationTest {

  /** Every field set to a value distinct from the defaults, so a dropped field shows. */
  private static final String EVERY_FIELD = """
      {
        "headlineLine1": "Line one edited.",
        "headlineLine2": "Line two edited.",
        "lede": "A different sentence.",
        "primaryCta": { "label": "Primary", "href": "/about#skills" },
        "secondaryCta": { "label": "Secondary", "href": "https://term-time.simonrowe.dev" },
        "showTourLink": false,
        "tourLinkLabel": "Tour label",
        "askPill": { "lead": "Curious?", "label": "Ask away", "buttonLabel": "Go" }
      }
      """;

  @Autowired
  private HomePageRepository repository;

  @BeforeEach
  void clear() {
    repository.deleteAll();
  }

  @Test
  void publicReadServesTheDefaultsBeforeAnythingIsSaved() throws Exception {
    mockMvc.perform(get("/api/home-page"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headlineLine1").value("Leading engineering teams."))
        .andExpect(jsonPath("$.headlineLine2").value("Building AI-native systems."))
        .andExpect(jsonPath("$.primaryCta.href").value("/about#roles"))
        .andExpect(jsonPath("$.showTourLink").value(true))
        .andExpect(jsonPath("$.askPill.label").value("Ask Simon anything"))
        .andExpect(jsonPath("$.updatedAt").doesNotExist());
  }

  @Test
  void everyFieldRoundTripsThroughTheEditorAndReachesThePublicRead() throws Exception {
    mockMvc.perform(put("/api/admin/home-page")
            .with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(EVERY_FIELD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    for (String path : new String[] {"/api/admin/home-page", "/api/home-page"}) {
      var request = get(path);
      if (path.startsWith("/api/admin")) {
        request = request.with(adminJwt());
      }
      mockMvc.perform(request)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.headlineLine1").value("Line one edited."))
          .andExpect(jsonPath("$.headlineLine2").value("Line two edited."))
          .andExpect(jsonPath("$.lede").value("A different sentence."))
          .andExpect(jsonPath("$.primaryCta.label").value("Primary"))
          .andExpect(jsonPath("$.primaryCta.href").value("/about#skills"))
          .andExpect(jsonPath("$.secondaryCta.label").value("Secondary"))
          .andExpect(jsonPath("$.secondaryCta.href").value("https://term-time.simonrowe.dev"))
          .andExpect(jsonPath("$.showTourLink").value(false))
          .andExpect(jsonPath("$.tourLinkLabel").value("Tour label"))
          .andExpect(jsonPath("$.askPill.lead").value("Curious?"))
          .andExpect(jsonPath("$.askPill.label").value("Ask away"))
          .andExpect(jsonPath("$.askPill.buttonLabel").value("Go"));
    }
    assertThat(repository.findAll()).singleElement()
        .satisfies(page -> assertThat(page.id()).isEqualTo(HomePage.SINGLETON_ID));
  }

  @Test
  void secondSaveReplacesTheFirstRatherThanAddingRows() throws Exception {
    for (int i = 0; i < 2; i++) {
      mockMvc.perform(put("/api/admin/home-page").with(adminJwt())
              .contentType(MediaType.APPLICATION_JSON).content(EVERY_FIELD))
          .andExpect(status().isOk());
    }
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void clearingTheOptionalSecondaryCtaRemovesIt() throws Exception {
    mockMvc.perform(put("/api/admin/home-page").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(EVERY_FIELD.replace(
                "\"secondaryCta\": { \"label\": \"Secondary\", \"href\": \"https://term-time.simonrowe.dev\" }",
                "\"secondaryCta\": { \"label\": \"\", \"href\": \"\" }")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secondaryCta").doesNotExist());
  }

  @Test
  void refusesAnOffSiteProtocolRelativeLinkAndNamesTheField() throws Exception {
    mockMvc.perform(put("/api/admin/home-page").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(EVERY_FIELD.replace("/about#skills", "//evil.example")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("primaryCta.href"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value(
            "primaryCta.href must be a site path starting with / or an https:// address"))
        .andExpect(jsonPath("$.message").isNotEmpty());
    assertThat(repository.count()).isZero();
  }

  @Test
  void refusesOverLongAndMissingFieldsOneErrorEach() throws Exception {
    mockMvc.perform(put("/api/admin/home-page").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(EVERY_FIELD
                .replace("Line one edited.", "x".repeat(HomePageValidator.HEADLINE_MAX + 1))
                .replace("\"buttonLabel\": \"Go\"", "\"buttonLabel\": \"\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.length()").value(2))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("headlineLine1"))
        .andExpect(jsonPath("$.fieldErrors[0].message")
            .value("headlineLine1 must be at most 60 characters"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("askPill.buttonLabel"));
  }

  @Test
  void requiresTourLabelOnlyWhenTheTourLinkIsShown() throws Exception {
    String hiddenAndBlank = EVERY_FIELD.replace("\"Tour label\"", "\"\"");
    mockMvc.perform(put("/api/admin/home-page").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON).content(hiddenAndBlank))
        .andExpect(status().isOk());
    mockMvc.perform(put("/api/admin/home-page").with(adminJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(hiddenAndBlank.replace("\"showTourLink\": false", "\"showTourLink\": true")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("tourLinkLabel"));
  }

  @Test
  void editingRequiresTheAdminRole() throws Exception {
    mockMvc.perform(put("/api/admin/home-page")
            .contentType(MediaType.APPLICATION_JSON).content(EVERY_FIELD))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(put("/api/admin/home-page").with(jwt())
            .contentType(MediaType.APPLICATION_JSON).content(EVERY_FIELD))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/home-page").with(jwt()))
        .andExpect(status().isForbidden());
    assertThat(repository.count()).isZero();
  }
}
