package com.simonrowe.factory.feedback.agent;

import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.git.RepositoryWorkspace;
import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Prepares a disposable checkout of a guidance repository (e.g. an agent-setup / instructions
 * repo) for an agent to edit, then validates and pushes the resulting changes.
 *
 * <p>The agent that edits files in the checkout never touches git or holds credentials: this
 * factory owns cloning, allowlist validation of the changed paths, and the commit/push. It is a
 * thin adapter over {@link RepositoryWorkspaceFactory} that supplies the feedback module's
 * workspace root, branch prefix and git identity from {@link FeedbackProperties}.
 */
@Component
public class GuidanceWorkspaceFactory {

  private final FeedbackProperties properties;
  private final RepositoryWorkspaceFactory workspaces;

  public GuidanceWorkspaceFactory(
      final FeedbackProperties properties, final RepositoryWorkspaceFactory workspaces) {
    this.properties = properties;
    this.workspaces = workspaces;
  }

  /** Shallow-clones the default branch of {@code owner/repository} into a temp workspace. */
  public RepositoryWorkspace create(
      final String owner,
      final String repository,
      final Long installationId,
      final Consumer<String> heartbeat) {
    return workspaces.create(
        owner, repository, installationId, properties.workspaceRoot(), "guidance-", heartbeat);
  }

  /** Paths touched in the workspace (git status --porcelain), repo-relative. */
  public List<String> changedPaths(
      final RepositoryWorkspace workspace, final Consumer<String> heartbeat) {
    return workspaces.changedPaths(workspace, heartbeat);
  }

  /** Branch + add + commit + force-push, using the feedback module's git identity. */
  public void commitAndPush(
      final RepositoryWorkspace workspace,
      final String branch,
      final String message,
      final Long installationId,
      final Consumer<String> heartbeat) {
    workspaces.commitAndPush(
        workspace, branch, message, properties.gitAuthorName(), properties.gitAuthorEmail(),
        installationId, heartbeat);
  }
}
