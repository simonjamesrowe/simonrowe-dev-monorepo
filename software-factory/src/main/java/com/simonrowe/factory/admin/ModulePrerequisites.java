package com.simonrowe.factory.admin;

import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.deploy.config.DeployProperties;
import com.simonrowe.factory.feedback.config.FeedbackProperties;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.platformbackup.config.PlatformBackupProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Separates "switched on" from "able to do its job", for every module in one place.
 *
 * <p>Feedback, vulnerability scanning, Linear filing and platform backup are all on by default
 * now, but each depends on a credential or a host path that defaults to empty. Left unchecked,
 * such a module reports itself enabled with a live Temporal poller and then fails on its first
 * real call — the exact confusion this class exists to prevent. Nothing here fails startup: a
 * missing prerequisite must degrade one module, never take the factory down.
 */
@Component
public class ModulePrerequisites {

  /** Display order, and the only place module keys are enumerated. */
  public static final List<String> KEYS =
      List.of("codereview", "feedback", "cvefix", "deploy", "linear", "platformbackup");

  private static final Logger LOG = LoggerFactory.getLogger(ModulePrerequisites.class);

  private final FeedbackProperties feedback;
  private final CveFixProperties cvefix;
  private final DeployProperties deploy;
  private final LinearProperties linear;
  private final PlatformBackupProperties platformBackup;

  public ModulePrerequisites(
      final FeedbackProperties feedback,
      final CveFixProperties cvefix,
      final DeployProperties deploy,
      final LinearProperties linear,
      final PlatformBackupProperties platformBackup) {
    this.feedback = feedback;
    this.cvefix = cvefix;
    this.deploy = deploy;
    this.linear = linear;
    this.platformBackup = platformBackup;
  }

  /**
   * Whether a module's own enable flag is on in this JVM.
   *
   * <p>Code review has no flag — it is the factory's original purpose and is always registered.
   * Deploy has two independent flags and counts as configured under either, because a container
   * that can only trigger and a container that can only execute are both meaningfully involved.
   *
   * @param key the module key
   * @return true when the module is switched on here
   */
  public boolean configured(final String key) {
    return switch (key) {
      case "codereview" -> true;
      case "feedback" -> feedback.enabled();
      case "cvefix" -> cvefix.enabled();
      case "deploy" -> deploy.enabled() || deploy.triggerEnabled();
      case "linear" -> linear.enabled();
      case "platformbackup" -> platformBackup.enabled();
      default -> false;
    };
  }

  /**
   * Lists what a module still needs before it can succeed.
   *
   * <p>A disabled module reports nothing: its unset credentials are not a fault, and reporting
   * them as one would bury the single fact that matters, which is that it is switched off.
   *
   * @param key the module key used by the status endpoint
   * @param isConfigured whether the module's own enable flag is on
   * @return the missing prerequisites, empty when the module is ready to work
   */
  public List<String> missingFor(final String key, final boolean isConfigured) {
    if (!isConfigured) {
      return List.of();
    }
    List<String> missing = new ArrayList<>();
    switch (key) {
      case "feedback" -> {
        // Not merely a nicety: the workflow files the Linear issue *before* distilling, and
        // fails non-retryably with LINEAR_DISABLED when it cannot, so with the sink off the
        // whole feedback loop stops rather than degrading to PR-only.
        if (!linear.enabled()) {
          missing.add("Linear filing is disabled, and feedback files its issue first");
        }
      }
      case "cvefix" -> {
        if (cvefix.dependencyTrack().apiKey().isBlank()) {
          missing.add("Dependency-Track API key is not set");
        }
        if (!linear.enabled()) {
          missing.add("Linear filing is disabled, so findings have nowhere to go");
        }
      }
      case "linear" -> {
        if (linear.apiKey().isBlank()) {
          missing.add("Linear API key is not set");
        }
        if (linear.teamKey().isBlank()) {
          missing.add("Linear team key is not set");
        }
      }
      case "platformbackup" -> missing.addAll(script(platformBackup.script(), "backup"));
      case "deploy" -> {
        if (deploy.enabled()) {
          missing.addAll(script(deploy.script(), "deploy"));
        }
      }
      default -> {
        // Code review needs no configuration beyond the GitHub App this container already
        // refuses to start without.
      }
    }
    return List.copyOf(missing);
  }

  /**
   * Logs the same findings once at startup.
   *
   * <p>The status page is the primary surface, but an operator who has just flipped a flag reads
   * container logs first, and a silent start is what made the previous defaults look healthy.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void reportAtStartup() {
    for (String key : KEYS) {
      if (!configured(key)) {
        continue;
      }
      List<String> missing = missingFor(key, true);
      if (missing.isEmpty()) {
        LOG.info("Factory module {} is enabled and its prerequisites are satisfied", key);
      } else {
        LOG.warn("Factory module {} is enabled but cannot work yet: {}", key,
            String.join("; ", missing));
      }
    }
  }

  /**
   * Both host scripts have been pointed at a path that stopped existing at least once, and the
   * symptom was a mid-flight "script not found" rather than anything visible beforehand.
   */
  private static List<String> script(final String path, final String label) {
    if (path == null || path.isBlank()) {
      return List.of("The " + label + " script path is not set");
    }
    try {
      return Files.isRegularFile(Path.of(path))
          ? List.of()
          : List.of("The " + label + " script is not present at its configured path");
    } catch (RuntimeException exception) {
      return List.of("The " + label + " script path cannot be read");
    }
  }
}
