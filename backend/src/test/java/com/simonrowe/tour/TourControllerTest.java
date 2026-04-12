package com.simonrowe.tour;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TourControllerTest extends AbstractIntegrationTest {

  @Autowired
  private TourStepRepository tourStepRepository;

  @BeforeEach
  void setup() {
    tourStepRepository.deleteAll();
  }

  @Test
  void getStepsReturnsOrderedSteps() throws Exception {
    tourStepRepository.saveAll(List.of(
        sampleStep("s-1", 3, ".contact", "Contact"),
        sampleStep("s-2", 1, ".banner", "Welcome"),
        sampleStep("s-3", 2, ".about", "About")
    ));

    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].order").value(1))
        .andExpect(jsonPath("$[0].title").value("Welcome"))
        .andExpect(jsonPath("$[0].targetSelector").value(".banner"))
        .andExpect(jsonPath("$[1].order").value(2))
        .andExpect(jsonPath("$[1].title").value("About"))
        .andExpect(jsonPath("$[2].order").value(3))
        .andExpect(jsonPath("$[2].title").value("Contact"));
  }

  @Test
  void getStepsReturnsEmptyListWhenNoSteps() throws Exception {
    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getStepsReturnsAllFields() throws Exception {
    tourStepRepository.save(new TourStep(
        "s-1", 1, ".homepage-banner", "Welcome",
        "/images/tour/welcome.png",
        "This is the **homepage banner**.",
        "bottom", "/", null
    ));

    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("s-1"))
        .andExpect(jsonPath("$[0].order").value(1))
        .andExpect(jsonPath("$[0].targetSelector").value(".homepage-banner"))
        .andExpect(jsonPath("$[0].title").value("Welcome"))
        .andExpect(jsonPath("$[0].titleImage").value("/images/tour/welcome.png"))
        .andExpect(jsonPath("$[0].description").value("This is the **homepage banner**."))
        .andExpect(jsonPath("$[0].position").value("bottom"));
  }

  @Test
  void getStepsReturnsNullTitleImage() throws Exception {
    tourStepRepository.save(sampleStep("s-1", 1, ".banner", "Welcome"));

    mockMvc.perform(get("/api/tour/steps"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].titleImage").doesNotExist());
  }

  private static TourStep sampleStep(final String id, final int order,
      final String selector, final String title) {
    return new TourStep(id, order, selector, title, null,
        "Description for " + title, "bottom", "/", null);
  }
}
