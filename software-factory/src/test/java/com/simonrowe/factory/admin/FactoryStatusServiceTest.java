package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.admin.FactoryStatusResponse.ModuleStatus;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.platformbackup.config.PlatformBackupProperties;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceBlockingStub;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleInfo;
import io.temporal.client.schedules.ScheduleIntervalSpec;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Configuration, a live worker poller and a schedule's pause state are three independent
 * statuses, and the page is only useful if it never collapses them. These tests hold that line,
 * including the two shapes that have cost real diagnosis time: a healthy container with no
 * poller, and an activity-only task queue that is correct with zero workflow pollers.
 */
class FactoryStatusServiceTest {

  private static final Instant NEXT_ACTION = Instant.parse("2026-09-01T02:00:00Z");
  private static final String LINEAR_KEY = "linear-key";
  private static final String TEAM_KEY = "SIM";

  @Test
  void reportsAllSixModulesInDisplayOrder() {
    FactoryStatusResponse status = service(queue -> Set.of()).status();

    assertThat(status.modules()).extracting(ModuleStatus::key)
        .containsExactlyElementsOf(ModulePrerequisites.KEYS);
    assertThat(status.container()).isEqualTo("software-factory");
  }

  @Test
  void marksModuleNotReadyWhenItHasNoPollerDespiteBeingEnabled() {
    // The failure mode the runbook warns about: healthcheck green, nothing polling, webhooks
    // accepted and silently never processed.
    ModuleStatus feedback = module(service(queue -> Set.of()), "feedback");

    assertThat(feedback.configured()).isTrue();
    assertThat(feedback.activityPollers()).isZero();
    assertThat(feedback.ready()).isFalse();
    assertThat(feedback.diagnostic()).isEqualTo("Required Temporal poller is missing");
  }

  @Test
  void marksModuleReadyOnlyWhenFlagPollersAndPrerequisitesAllAgree() {
    ModuleStatus feedback = module(service(queue -> BOTH), "feedback");

    assertThat(feedback.ready()).isTrue();
    assertThat(feedback.workflowPollers()).isEqualTo(1);
    assertThat(feedback.activityPollers()).isEqualTo(1);
    assertThat(feedback.diagnostic()).isNull();
  }

  @Test
  void acceptsAnActivityOnlyLinearQueue() {
    // The linear queue has one activity poller and zero workflow pollers by design. Asserting a
    // workflow poller there would report the correct shape as broken forever.
    ModuleStatus linear =
        module(service(queue -> "linear".equals(queue) ? ACTIVITY_ONLY : BOTH), "linear");

    assertThat(linear.workflowPollers()).isZero();
    assertThat(linear.activityPollers()).isEqualTo(1);
    assertThat(linear.ready()).isTrue();
  }

  @Test
  void distinguishesDisabledModuleFromBrokenOne() {
    ModuleStatus deploy = module(service(queue -> BOTH), "deploy");

    assertThat(deploy.configured()).isFalse();
    assertThat(deploy.ready()).isFalse();
    assertThat(deploy.diagnostic()).isEqualTo("Disabled by configuration");
  }

  @Test
  void reportsUnknownRatherThanZeroPollersWhenTemporalCannotBeReached() {
    // Zero pollers and "we could not ask" lead an operator to different actions, so the two must
    // not be reported the same way.
    ModuleStatus feedback = module(unreachableTemporal(), "feedback");

    assertThat(feedback.workflowPollers()).isNull();
    assertThat(feedback.activityPollers()).isNull();
    assertThat(feedback.diagnostic()).isEqualTo("Temporal task queue status is unavailable");
  }

  @Test
  void reportsAnEnabledModuleThatCannotWorkSeparatelyFromOneThatIsOff() {
    // Vulnerability scanning is on by default with no Dependency-Track key set, which is neither
    // "off" nor "healthy" — and was invisible before.
    ModuleStatus cvefix = module(service(queue -> BOTH), "cvefix");

    assertThat(cvefix.configured()).isTrue();
    assertThat(cvefix.missingPrerequisites())
        .containsExactly("Dependency-Track API key is not set");
    assertThat(cvefix.ready()).isFalse();
    assertThat(cvefix.diagnostic())
        .isEqualTo("Enabled but not usable: Dependency-Track API key is not set");
  }

  @Test
  void reportsScheduleStateForScheduledModulesOnly() {
    FactoryStatusService service = service(queue -> BOTH, false);

    assertThat(module(service, "cvefix").schedule()).isNotNull();
    assertThat(module(service, "platformbackup").schedule()).isNotNull();
    assertThat(module(service, "feedback").schedule()).isNull();
    assertThat(module(service, "codereview").schedule()).isNull();
    assertThat(module(service, "cvefix").schedule().paused()).isFalse();
    assertThat(module(service, "cvefix").schedule().exists()).isTrue();
  }

  @Test
  void reportsPausedScheduleAsPausedRatherThanAbsent() {
    // An operator pause is a deliberate state the page must show, and it is not a fault.
    assertThat(module(service(queue -> BOTH, true), "cvefix").schedule().paused()).isTrue();
  }

  @Test
  void reportsAnAbsentScheduleWithoutFailingTheWholeStatus() {
    FactoryStatusService service =
        new FactoryStatusService(
            workflowClient(queue -> BOTH), noSchedules(), prerequisites(), "deployer");

    FactoryStatusResponse status = service.status();

    assertThat(status.container()).isEqualTo("deployer");
    assertThat(module(status, "cvefix").schedule().exists()).isFalse();
    assertThat(module(status, "cvefix").schedule().diagnostic())
        .isEqualTo("Schedule is absent or unavailable");
  }

  private static final Set<TaskQueueType> BOTH =
      Set.of(
          TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW, TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY);

  private static final Set<TaskQueueType> ACTIVITY_ONLY =
      Set.of(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY);

  private static ModuleStatus module(final FactoryStatusService service, final String key) {
    return module(service.status(), key);
  }

  private static ModuleStatus module(final FactoryStatusResponse status, final String key) {
    return status.modules().stream()
        .filter(candidate -> key.equals(candidate.key()))
        .findFirst()
        .orElseThrow();
  }

  private static FactoryStatusService service(
      final Function<String, Set<TaskQueueType>> pollers) {
    return service(pollers, false);
  }

  private static FactoryStatusService service(
      final Function<String, Set<TaskQueueType>> pollers, final boolean paused) {
    return new FactoryStatusService(
        workflowClient(pollers), schedules(paused), prerequisites(), "software-factory");
  }

  private static FactoryStatusService unreachableTemporal() {
    WorkflowClient client = mock(WorkflowClient.class);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    when(client.getWorkflowServiceStubs())
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
    return new FactoryStatusService(
        client, schedules(false), prerequisites(), "software-factory");
  }

  private static WorkflowClient workflowClient(
      final Function<String, Set<TaskQueueType>> pollers) {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
    WorkflowServiceBlockingStub blockingStub = mock(WorkflowServiceBlockingStub.class);
    when(client.getOptions())
        .thenReturn(WorkflowClientOptions.newBuilder().setNamespace("default").build());
    when(client.getWorkflowServiceStubs()).thenReturn(stubs);
    when(stubs.blockingStub()).thenReturn(blockingStub);
    when(blockingStub.describeTaskQueue(any())).thenAnswer(invocation -> {
      DescribeTaskQueueRequest request = invocation.getArgument(0);
      boolean present =
          pollers.apply(request.getTaskQueue().getName()).contains(request.getTaskQueueType());
      DescribeTaskQueueResponse.Builder response = DescribeTaskQueueResponse.newBuilder();
      if (present) {
        response.addPollers(PollerInfo.newBuilder().setIdentity("worker").build());
      }
      return response.build();
    });
    return client;
  }

  private static ScheduleClient schedules(final boolean paused) {
    ScheduleClient client = mock(ScheduleClient.class);
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(client.getHandle(any())).thenReturn(handle);
    when(handle.describe()).thenAnswer(invocation -> description(paused));
    return client;
  }

  private static ScheduleClient noSchedules() {
    ScheduleClient client = mock(ScheduleClient.class);
    ScheduleHandle handle = mock(ScheduleHandle.class);
    when(client.getHandle(any())).thenReturn(handle);
    when(handle.describe()).thenThrow(new StatusRuntimeException(Status.NOT_FOUND));
    return client;
  }

  private static ScheduleDescription description(final boolean paused) {
    Schedule schedule =
        Schedule.newBuilder()
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType("Workflow")
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("workflow")
                            .setTaskQueue("queue")
                            .build())
                    .build())
            .setSpec(
                ScheduleSpec.newBuilder()
                    .setIntervals(List.of(new ScheduleIntervalSpec(Duration.ofHours(24))))
                    .build())
            .setPolicy(
                SchedulePolicy.newBuilder()
                    .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                    .build())
            .setState(ScheduleState.newBuilder().setPaused(paused).build())
            .build();
    return new ScheduleDescription(
        "schedule",
        new ScheduleInfo(
            0L, 0L, 0L, List.of(), List.of(), List.of(NEXT_ACTION), null, null),
        schedule,
        Map.of(),
        null,
        Map.of(),
        null);
  }

  private static ModulePrerequisites prerequisites() {
    return new ModulePrerequisites(
        new FeedbackProperties(true, null, null, null, null, null, null, null, null),
        new CveFixProperties(true, null, null, null, null, null, null, null, null, null, null),
        new DeployProperties(
            false, false, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null),
        new LinearProperties(true, LINEAR_KEY, null, TEAM_KEY, null, false, null, null),
        new PlatformBackupProperties(false, null, null, null));
  }

}
