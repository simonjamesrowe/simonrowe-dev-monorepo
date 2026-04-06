package com.simonrowe.embedding;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

  @Bean
  public TokenTextSplitter tokenTextSplitter() {
    return new TokenTextSplitter(500, 100, 5, 100, true, null);
  }
}
