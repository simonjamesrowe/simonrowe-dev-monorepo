package com.simonrowe.search.elasticsearch;

import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the Elasticsearch client's JSON-P mapper.
 *
 * <p>This builds its own {@link ObjectMapper} rather than injecting the application's, which is
 * what it used to do. Since the Spring Boot 4 upgrade the two are no longer the same library:
 * the context's mapper is Jackson 3 ({@code tools.jackson.databind}), while
 * {@link JacksonJsonpMapper} is compiled against Jackson 2
 * ({@code com.fasterxml.jackson.databind}) and accepts only that type. Both lines are on the
 * classpath — Boot 4 manages {@code jackson-bom} (3.x) and {@code jackson-2-bom} (2.x) side by
 * side — but {@code spring-boot-jackson2} is not, so there is no Jackson 2 {@code ObjectMapper}
 * bean to inject even if one wanted to.
 *
 * <p>The two settings below are not decoration; they reproduce what the injected Boot mapper
 * used to provide, and each fails in its own way without them:
 *
 * <ul>
 *   <li>{@code findAndAddModules()} registers {@code jackson-datatype-jsr310}. Jackson 2 refuses
 *       to serialise {@link java.time.Instant} without it, so indexing any document carrying a
 *       timestamp throws {@code InvalidDefinitionException}.
 *   <li>Disabling {@code WRITE_DATES_AS_TIMESTAMPS} keeps dates as ISO-8601 strings. Leaving it
 *       at the Jackson default would write epoch numbers instead and silently change the mapping
 *       Elasticsearch infers for every date field.
 * </ul>
 */
@Configuration
public class ElasticsearchJsonpMapperConfig {

  @Bean
  public JacksonJsonpMapper jacksonJsonpMapper() {
    ObjectMapper objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    return new JacksonJsonpMapper(objectMapper);
  }
}
