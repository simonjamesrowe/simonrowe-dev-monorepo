package com.simonrowe.factory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the software factory. Today it hosts one module, {@code codereview}, serving its GitHub
 * webhook and running its Temporal worker in the same JVM; further modules are expected to sit
 * beside it under {@code com.simonrowe.factory}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FactoryApplication {

  public static void main(String[] args) {
    SpringApplication.run(FactoryApplication.class, args);
  }
}
