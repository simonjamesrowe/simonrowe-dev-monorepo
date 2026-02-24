package com.simonrowe.search.elasticsearch;

import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchJsonpMapperConfig {

  @Bean
  public JacksonJsonpMapper jacksonJsonpMapper(final ObjectMapper objectMapper) {
    return new JacksonJsonpMapper(objectMapper);
  }
}
