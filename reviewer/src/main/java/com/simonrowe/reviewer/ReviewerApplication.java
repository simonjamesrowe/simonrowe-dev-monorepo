package com.simonrowe.reviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Starts the durable code-review API and Temporal worker. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ReviewerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ReviewerApplication.class, args);
  }
}
