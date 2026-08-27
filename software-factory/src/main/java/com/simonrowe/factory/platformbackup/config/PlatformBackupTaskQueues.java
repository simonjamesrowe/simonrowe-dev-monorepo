package com.simonrowe.factory.platformbackup.config;

/** Temporal task queue names for the platform backup workflow. */
public final class PlatformBackupTaskQueues {

  /**
   * Task queue polled by the platform backup workflow and its activity.
   *
   * <p>Its own queue rather than sharing {@code deploy}: a long capture must never sit in front of
   * a deploy, and the two have very different timeouts.
   *
   * <p>Both {@code software-factory} and {@code deployer} run the same image, so both register a
   * workflow-task poller here — {@code @WorkflowImpl} scanning is unconditional. Only
   * {@code deployer} holds the activity bean, gated on {@code factory.platform-backup.enabled}, so
   * only {@code deployer} can execute the capture.
   */
  public static final String PLATFORM_BACKUP = "platform-backup";

  private PlatformBackupTaskQueues() {
  }
}
