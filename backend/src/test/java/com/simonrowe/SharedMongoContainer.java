package com.simonrowe;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MongoDBContainer;

public final class SharedMongoContainer {

  static final MongoDBContainer MONGODB = new MongoDBContainer("mongo:8");

  static {
    MONGODB.start();
  }

  private SharedMongoContainer() {
  }

  public static void configureProperties(final DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", MONGODB::getReplicaSetUrl);
  }
}
