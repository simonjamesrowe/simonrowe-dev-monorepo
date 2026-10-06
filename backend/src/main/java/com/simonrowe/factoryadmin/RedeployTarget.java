package com.simonrowe.factoryadmin;

import com.simonrowe.platform.FactoryVersionClient;
import com.simonrowe.platform.PlatformRelease;
import com.simonrowe.platform.PlatformReleaseRepository;
import com.simonrowe.platform.RunningVersion;
import com.simonrowe.platform.ServiceVersion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decides which commit a manual redeploy deploys.
 *
 * <p>The rule used to be "frontend and backend must report the same commit, and that commit is
 * redeployed". Publish now rebuilds only the images whose code changed and gives the others the
 * new commit's tag, so the services routinely report different commits and that rule would
 * refuse every redeploy after a frontend-only merge.
 *
 * <p><b>The rule now: redeploy the newest commit any deployed service was built from.</b> Every
 * Publish run tags all three images with its commit, and a service's image at any commit is the
 * one built at its last change. So at the newest of the three build commits, every service's
 * image is exactly the one running now: nothing is upgraded and nothing is rolled back. Any older
 * commit would roll back whichever service was built after it. If the deploy directory is
 * already past that commit, which it is after a docs-only merge, {@code sync-config}'s
 * fast-forward is a no-op and leaves it where it is.
 *
 * <p>The frontend's commit can only come from the browser, since the backend cannot see which
 * bundle was loaded. It is accepted only as a commit already recorded from {@code main}'s
 * history, and its time is read from that record, never from the browser.
 */
@Component
public class RedeployTarget {

  private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}");
  private static final String UNKNOWN_COMMIT = "unknown";
  private static final String FACTORY = "software-factory";
  private static final int SHORT_COMMIT = 7;

  private final RunningVersion runningVersion;
  private final FactoryVersionClient factoryVersionClient;
  private final PlatformReleaseRepository releases;

  /**
   * Creates the resolver.
   *
   * @param runningVersion the backend's own version
   * @param factoryVersionClient reports the software-factory's version
   * @param releases {@code main}'s recorded history, used to date the frontend's commit
   */
  public RedeployTarget(
      final RunningVersion runningVersion,
      final FactoryVersionClient factoryVersionClient,
      final PlatformReleaseRepository releases) {
    this.runningVersion = runningVersion;
    this.factoryVersionClient = factoryVersionClient;
    this.releases = releases;
  }

  /**
   * The server-side services' commits, for the console to show and to work out the same target.
   *
   * @return the backend's and the software-factory's commits, each only when it is known
   */
  public List<ServiceCommit> serverSideCommits() {
    List<ServiceCommit> commits = new ArrayList<>();
    backend().ifKnown(commits);
    factory().ifKnown(commits);
    return List.copyOf(commits);
  }

  /**
   * The commit a redeploy must target.
   *
   * @param frontendCommit the commit the browser's bundle reports
   * @return the newest of the backend's, the frontend's and the software-factory's commits;
   *     on equal times the earlier of those three wins, matching the console
   * @throws ResponseStatusException 412 when any of the three cannot be established
   */
  public ServiceCommit resolve(final String frontendCommit) {
    List<ServiceCommit> candidates = List.of(
        backend().require("The backend is not reporting its commit"),
        frontend(frontendCommit),
        factory().require("software-factory is not reporting its commit"));
    ServiceCommit newest = candidates.get(0);
    for (ServiceCommit candidate : candidates) {
      if (candidate.commitTime().isAfter(newest.commitTime())) {
        newest = candidate;
      }
    }
    return newest;
  }

  private Known backend() {
    return new Known("backend", runningVersion.commit(), runningVersion.commitTime());
  }

  private Known factory() {
    for (ServiceVersion version : factoryVersionClient.versions()) {
      if (FACTORY.equals(version.name()) && version.reachable()) {
        return new Known(FACTORY, version.commit(), version.commitTime());
      }
    }
    return new Known(FACTORY, null, null);
  }

  private ServiceCommit frontend(final String commit) {
    if (commit == null || !FULL_SHA.matcher(commit).matches()) {
      throw precondition("The frontend is not reporting a full commit SHA");
    }
    Instant committed = releases.findById(commit)
        .map(PlatformRelease::getCommitTime)
        .orElseThrow(() -> precondition("""
            The frontend's commit %s is not in the release history yet; \
            try again in a few minutes""".formatted(commit.substring(0, SHORT_COMMIT))));
    return new ServiceCommit("frontend", commit, committed);
  }

  private static ResponseStatusException precondition(final String message) {
    return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, message);
  }

  /**
   * A service's commit as reported, which may be missing.
   *
   * @param service the service name
   * @param commit the reported SHA, or null
   * @param commitTime the reported commit time, or null
   */
  private record Known(String service, String commit, Instant commitTime) {

    boolean known() {
      return commit != null && !UNKNOWN_COMMIT.equals(commit) && commitTime != null;
    }

    void ifKnown(final List<ServiceCommit> into) {
      if (known()) {
        into.add(new ServiceCommit(service, commit, commitTime));
      }
    }

    ServiceCommit require(final String message) {
      if (!known()) {
        throw precondition(message);
      }
      return new ServiceCommit(service, commit, commitTime);
    }
  }
}
