package com.simonrowe.factory.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import com.simonrowe.factory.logwatch.workflow.ScanObservation;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TemporalPayloadConfigurationTest {

  /** {@link ScanObservation} as it was before {@code mutedSignatures} and {@code mutedBy}. */
  record ScanObservationBeforeMuting(
      SourceHealth sourceHealth,
      List<LogSignature> signatures,
      int linesRead,
      boolean truncated,
      int containersSeen,
      int signaturesDropped) {
  }

  private final DataConverter converter = new TemporalPayloadConfiguration().mainDataConverter();

  private static Payload writtenByNewerBuild() {
    ScanObservation observation =
        new ScanObservation(
            new SourceHealth(
                SourceHealth.Status.ALIVE,
                SourceHealth.Tier.CONTAINER_COVERAGE,
                "12 containers reporting"),
            List.of(),
            480,
            false,
            12,
            0,
            3,
            List.of("third-party noise"));
    return DefaultDataConverter.STANDARD_INSTANCE.toPayload(observation).orElseThrow();
  }

  @Test
  @DisplayName("an older build reads a result that carries fields it has never heard of")
  void olderReaderIgnoresFieldsAddedByNewerWriter() {
    ScanObservationBeforeMuting read =
        converter.fromPayload(
            writtenByNewerBuild(),
            ScanObservationBeforeMuting.class,
            ScanObservationBeforeMuting.class);

    assertThat(read.linesRead()).isEqualTo(480);
    assertThat(read.containersSeen()).isEqualTo(12);
    assertThat(read.sourceHealth().status()).isEqualTo(SourceHealth.Status.ALIVE);
  }

  @Test
  @DisplayName("control: Temporal's stock converter throws on the same payload, as it did on 09-15")
  void stockConverterRejectsTheSamePayload() {
    assertThatThrownBy(
            () ->
                DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
                    writtenByNewerBuild(),
                    ScanObservationBeforeMuting.class,
                    ScanObservationBeforeMuting.class))
        .isInstanceOf(DataConverterException.class)
        .hasStackTraceContaining("mutedSignatures");
  }

  @Test
  void roundTripsTheCurrentShapeUnchanged() {
    Payload payload = writtenByNewerBuild();

    ScanObservation read =
        converter.fromPayload(payload, ScanObservation.class, ScanObservation.class);

    assertThat(read.mutedSignatures()).isEqualTo(3);
    assertThat(read.mutedBy()).containsExactly("third-party noise");
    assertThat(converter.toPayload(read).orElseThrow()).isEqualTo(payload);
  }
}
