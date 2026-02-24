package com.simonrowe.tour;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataMongoTest
@Testcontainers
class TourStepRepositoryTest {

  @Container
  static MongoDBContainer mongodb = new MongoDBContainer("mongo:8");

  @Autowired
  private TourStepRepository tourStepRepository;

  @DynamicPropertySource
  static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongodb::getReplicaSetUrl);
  }

  @BeforeEach
  void setup() {
    tourStepRepository.deleteAll();
  }

  @Test
  void findAllByOrderByOrderAscReturnsSortedSteps() {
    tourStepRepository.saveAll(List.of(
        new TourStep("s-3", 3, ".contact", "Contact", null, "Contact desc", "top"),
        new TourStep("s-1", 1, ".banner", "Welcome", null, "Welcome desc", "bottom"),
        new TourStep("s-2", 2, ".about", "About", null, "About desc", "top")
    ));

    final List<TourStep> steps = tourStepRepository.findAllByOrderByOrderAsc();

    assertThat(steps).hasSize(3);
    assertThat(steps.get(0).order()).isEqualTo(1);
    assertThat(steps.get(0).title()).isEqualTo("Welcome");
    assertThat(steps.get(1).order()).isEqualTo(2);
    assertThat(steps.get(1).title()).isEqualTo("About");
    assertThat(steps.get(2).order()).isEqualTo(3);
    assertThat(steps.get(2).title()).isEqualTo("Contact");
  }

  @Test
  void findAllByOrderByOrderAscReturnsEmptyForNoDocuments() {
    final List<TourStep> steps = tourStepRepository.findAllByOrderByOrderAsc();

    assertThat(steps).isEmpty();
  }

  @Test
  void savePersistsAllFields() {
    final TourStep step = new TourStep(
        "s-1", 1, ".homepage-banner", "Welcome",
        "/images/tour/welcome.png",
        "This is the **homepage banner**.",
        "bottom"
    );
    tourStepRepository.save(step);

    final TourStep saved = tourStepRepository.findById("s-1").orElseThrow();

    assertThat(saved.order()).isEqualTo(1);
    assertThat(saved.targetSelector()).isEqualTo(".homepage-banner");
    assertThat(saved.title()).isEqualTo("Welcome");
    assertThat(saved.titleImage()).isEqualTo("/images/tour/welcome.png");
    assertThat(saved.description()).isEqualTo("This is the **homepage banner**.");
    assertThat(saved.position()).isEqualTo("bottom");
  }

  @Test
  void saveHandlesNullTitleImage() {
    final TourStep step = new TourStep(
        "s-1", 1, ".banner", "Welcome", null, "Description", "bottom"
    );
    tourStepRepository.save(step);

    final TourStep saved = tourStepRepository.findById("s-1").orElseThrow();

    assertThat(saved.titleImage()).isNull();
  }
}
