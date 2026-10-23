package com.simonrowe.factory.flow;

import com.simonrowe.factory.admin.ModulePrerequisites;
import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.FlowEdge;
import com.simonrowe.factory.flow.domain.Loop;
import com.simonrowe.factory.flow.domain.NodeDescriptor;
import com.simonrowe.factory.flow.domain.NodeKind;
import java.util.List;

/**
 * The factory's shape, as code.
 *
 * <p>This is deliberately not configuration and not data. The topology is a property of which
 * modules exist and what they pass between them, so it changes only when the code changes — and
 * {@code FactoryFlowTopologyTest} fails the build when a module is added without being drawn,
 * which is the failure {@code FactoryAdminService.ORDER} has today.
 */
public final class FactoryFlowTopology {

  /**
   * The ticket agent's node key. Still {@code build}, the key 044 drew it under, so a saved
   * selection or a link to the node keeps working; its module is {@code ticketagent}.
   */
  public static final String BUILD = "build";

  /** The Linear artifact node. Carries the activity-only linear module's health as a badge. */
  public static final String LINEAR_NODE = "linear";

  /** Every node, in no particular order; the frontend lays them out by band. */
  public static final List<NodeDescriptor> NODES =
      List.of(
          new NodeDescriptor("logwatch", NodeKind.MODULE, Band.OBSERVE, "Log watch",
              ModulePrerequisites.LOGWATCH, "LogWatchWorkflow"),
          new NodeDescriptor("cvefix", NodeKind.MODULE, Band.OBSERVE, "Vulnerability scan",
              ModulePrerequisites.CVEFIX, "CveFixWorkflow"),
          new NodeDescriptor("staticanalysis", NodeKind.MODULE, Band.OBSERVE, "Static analysis",
              ModulePrerequisites.STATIC_ANALYSIS, "StaticAnalysisWorkflow"),
          new NodeDescriptor(LINEAR_NODE, NodeKind.ARTIFACT, Band.PLAN, "Linear",
              ModulePrerequisites.LINEAR, null),
          new NodeDescriptor(BUILD, NodeKind.MODULE, Band.BUILD, "Ticket agent",
              ModulePrerequisites.TICKET_AGENT, "TicketAgentWorkflow"),
          new NodeDescriptor("pull-request", NodeKind.ARTIFACT, Band.BUILD, "Pull request",
              null, null),
          new NodeDescriptor("codereview", NodeKind.MODULE, Band.BUILD, "Code review",
              ModulePrerequisites.CODE_REVIEW, "CodeReviewWorkflow"),
          new NodeDescriptor("main", NodeKind.ARTIFACT, Band.SHIP, "main", null, null),
          new NodeDescriptor("deploy", NodeKind.MODULE, Band.SHIP, "Deploy",
              ModulePrerequisites.DEPLOY, "DeployWorkflow"),
          new NodeDescriptor("production", NodeKind.ARTIFACT, Band.SHIP, "Production", null, null),
          new NodeDescriptor("feedback", NodeKind.MODULE, Band.LEARN, "Feedback",
              ModulePrerequisites.FEEDBACK, "ReviewFeedbackWorkflow"),
          new NodeDescriptor("agent-setup", NodeKind.ARTIFACT, Band.LEARN, "agent-setup",
              null, null),
          new NodeDescriptor("platformbackup", NodeKind.MODULE, Band.UTILITY, "Platform backup",
              ModulePrerequisites.PLATFORM_BACKUP, "PlatformBackupWorkflow"));

  /**
   * Every edge. Platform backup deliberately appears in none of them.
   */
  public static final List<FlowEdge> EDGES =
      List.of(
          new FlowEdge("pull-request", "codereview", "push webhook", Loop.FAST),
          new FlowEdge("codereview", "pull-request", "findings and check run", Loop.FAST),
          // The reviewer arms GitHub auto-merge; GitHub performs the merge once the ruleset is
          // satisfied. So this edge is "arms", never "merges", and `pull-request -> main` below
          // stays the edge a merge actually travels.
          new FlowEdge("codereview", "main", "arms auto-merge", Loop.FAST),
          // CI's workflow_run webhook wakes the agent to repair a red build. This loop is the
          // whole reason the agent can run without building anything itself.
          new FlowEdge("pull-request", BUILD, "CI red: repair (max 3)", Loop.FAST),

          new FlowEdge("production", "logwatch", "reads Loki", Loop.MAIN),
          new FlowEdge("logwatch", LINEAR_NODE, "files signature", Loop.MAIN),
          new FlowEdge("main", "cvefix", "publishes SBOMs", Loop.MAIN),
          new FlowEdge("cvefix", LINEAR_NODE, "files vulnerabilities", Loop.MAIN),
          // SonarCloud analyses main on every push; the module reads that analysis nightly. Pull
          // requests are no longer analysed, which is the point: Sonar's findings arrive as
          // tickets after the merge instead of as a slow check before it.
          new FlowEdge("main", "staticanalysis", "SonarCloud analyses main", Loop.MAIN),
          new FlowEdge("staticanalysis", LINEAR_NODE, "files findings", Loop.MAIN),
          new FlowEdge(LINEAR_NODE, BUILD, "approved: factory:agent", Loop.MAIN),
          new FlowEdge(BUILD, "pull-request", "opens", Loop.MAIN),
          new FlowEdge(BUILD, LINEAR_NODE, "done on merge, or handed back", Loop.MAIN),
          new FlowEdge("pull-request", "main", "merges when green", Loop.MAIN),
          new FlowEdge("main", "deploy", "Publish webhook", Loop.MAIN),
          new FlowEdge("deploy", "production", "recreates", Loop.MAIN),
          new FlowEdge("deploy", "logwatch", "scan after five minutes", Loop.MAIN),
          new FlowEdge("deploy", LINEAR_NODE, "files failure", Loop.MAIN),

          new FlowEdge("pull-request", "feedback", "on close", Loop.SLOW),
          new FlowEdge("feedback", "agent-setup", "guidance pull request", Loop.SLOW),
          // Guidance pull requests merge themselves when every path is in the distill allowlist.
          new FlowEdge("codereview", "agent-setup", "arms auto-merge", Loop.SLOW),
          new FlowEdge("agent-setup", BUILD, "shapes the agent", Loop.SLOW),
          new FlowEdge("agent-setup", "codereview", "shapes the reviewer", Loop.SLOW));

  private FactoryFlowTopology() {
  }
}
