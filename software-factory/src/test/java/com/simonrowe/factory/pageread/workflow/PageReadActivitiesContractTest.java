package com.simonrowe.factory.pageread.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.pageread.config.PageReadTaskQueues;
import com.simonrowe.factory.pageread.domain.PageReadOutcome;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import io.temporal.activity.ActivityMethod;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The two halves of a cross-language activity agree only by name, and nothing at runtime
 * reports a mismatch: a workflow scheduling an activity type or queue nobody polls just waits
 * until its schedule-to-close timeout. So the Node source is read here and held to the Java
 * side: queue, activity name, outcomes and result fields.
 *
 * <p>{@code page-reader/} is declared as an input of this module's test task, or a change to
 * the Node side alone would be served a cached pass.
 */
class PageReadActivitiesContractTest {

  private static final Path PAGE_READER = Path.of("..", "page-reader", "src");

  private static String source(final String file) throws IOException {
    return Files.readString(PAGE_READER.resolve(file));
  }

  @Test
  void theNodeWorkerPollsTheQueueTheWorkflowSchedulesOn() throws IOException {
    assertThat(source("worker.ts"))
        .contains("TASK_QUEUE = '%s'".formatted(PageReadTaskQueues.PAGE_READER));
  }

  @Test
  void theNodeWorkerRegistersTheActivityUnderItsJavaName() throws Exception {
    String name = PageReadActivities.class.getMethod("readPage",
        com.simonrowe.factory.pageread.domain.PageReadInput.class)
        .getAnnotation(ActivityMethod.class).name();

    assertThat(source("worker.ts")).contains("async %s(input: ReadPageInput)".formatted(name));
  }

  @Test
  void everyOutcomeTheNodeSideReturnsExistsHere() throws IOException {
    Matcher outcomes =
        Pattern.compile("export type ReadOutcome = ([^;]+);").matcher(source("readPage.ts"));
    assertThat(outcomes.find()).isTrue();
    List<String> node = Arrays.stream(outcomes.group(1).split("\\|"))
        .map(value -> value.trim().replace("'", ""))
        .toList();

    assertThat(node).containsExactlyInAnyOrder(
        Arrays.stream(PageReadOutcome.values()).map(Enum::name).toArray(String[]::new));
  }

  @Test
  void theResultFieldsMatchTheNodeInterface() throws IOException {
    Matcher block = Pattern.compile("export interface ReadPageResult \\{([^}]+)}", Pattern.DOTALL)
        .matcher(source("readPage.ts"));
    assertThat(block.find()).isTrue();
    List<String> node = Pattern.compile("(\\w+):").matcher(block.group(1)).results()
        .map(match -> match.group(1)).toList();

    assertThat(node).containsExactlyElementsOf(
        Arrays.stream(PageReadResult.class.getRecordComponents())
            .map(RecordComponent::getName).toList());
  }
}
