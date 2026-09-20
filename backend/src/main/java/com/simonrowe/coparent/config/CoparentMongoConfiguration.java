package com.simonrowe.coparent.config;

import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/** Routes CoParent repositories to their own database on the shared Mongo server. */
@Configuration(proxyBeanMethods = false)
public class CoparentMongoConfiguration {

  /** Explicit primary template because defining a second template disables Boot's default bean. */
  @Bean
  @Primary
  MongoTemplate mongoTemplate(
      final MongoDatabaseFactory databaseFactory,
      final MongoConverter mongoConverter) {
    return new MongoTemplate(databaseFactory, mongoConverter);
  }

  /** CoParent database template sharing the application's configured Mongo client. */
  @Bean("coparentMongoTemplate")
  MongoTemplate coparentMongoTemplate(
      final MongoClient mongoClient,
      final CoparentProperties properties) {
    return new MongoTemplate(mongoClient, properties.database());
  }

  /** Registers every non-CoParent repository against the primary simonrowe database. */
  @Configuration(proxyBeanMethods = false)
  @EnableMongoRepositories(
      basePackages = "com.simonrowe",
      mongoTemplateRef = "mongoTemplate",
      excludeFilters = {
          @ComponentScan.Filter(
              type = FilterType.REGEX,
              pattern = "com\\.simonrowe\\.coparent\\.persistence\\..*"),
          @ComponentScan.Filter(
              type = FilterType.ASSIGNABLE_TYPE,
              classes = ElasticsearchRepository.class)
      })
  static class PrimaryRepositories {
  }

  /** Registers the product module's repositories against the dedicated CoParent database. */
  @Configuration(proxyBeanMethods = false)
  @EnableMongoRepositories(
      basePackages = "com.simonrowe.coparent.persistence",
      mongoTemplateRef = "coparentMongoTemplate")
  static class CoparentRepositories {
  }
}
