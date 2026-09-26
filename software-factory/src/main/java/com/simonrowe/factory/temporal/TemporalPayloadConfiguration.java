package com.simonrowe.factory.temporal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.JacksonJsonPayloadConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes every Temporal payload tolerate fields its reader does not know.
 *
 * <p>{@code software-factory} and {@code deployer} run the same image but are restarted at
 * different moments, and workers auto-discovery registers every workflow on both. So a workflow
 * task and the activity it scheduled can run on two different builds. When a build adds a field to
 * an activity result, the older build's strict Jackson reader throws on it. On 2026-09-15 that is
 * what happened to {@code ScanObservation.mutedSignatures}: the log-watch run recorded its
 * failure path, the next replay on the newer build took the success path, and the
 * nondeterministic run blocked {@code logwatch-daily} for eleven days.
 *
 * <p>Ignoring unknown properties removes the whole class of failure for additive changes, which
 * are the overwhelming majority, without anyone having to remember an annotation on each record.
 * Removing or renaming a field is still a breaking change and still needs a versioned rollout.
 */
@Configuration
public class TemporalPayloadConfiguration {

  /**
   * The data converter the Temporal starter uses for clients and workers alike.
   *
   * <p>Named {@code mainDataConverter} because that is the name the starter resolves first when
   * more than one {@link DataConverter} bean exists.
   *
   * @return the default converter chain with a lenient JSON payload converter
   */
  @Bean(name = "mainDataConverter")
  public DataConverter mainDataConverter() {
    return DefaultDataConverter.newDefaultInstance()
        .withPayloadConverterOverrides(new JacksonJsonPayloadConverter(lenientObjectMapper()));
  }

  static ObjectMapper lenientObjectMapper() {
    return JacksonJsonPayloadConverter.newDefaultObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }
}
