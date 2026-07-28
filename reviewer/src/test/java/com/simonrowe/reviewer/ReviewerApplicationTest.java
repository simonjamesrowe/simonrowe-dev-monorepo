package com.simonrowe.reviewer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.profiles.active=api")
class ReviewerApplicationTest {

  @Test
  void applicationContextLoadsInApiRole() {
  }
}
