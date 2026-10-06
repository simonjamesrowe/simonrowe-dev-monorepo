package com.simonrowe.platform;

import java.util.List;

/**
 * Where the changelog's commits come from.
 *
 * <p>Two calls rather than one because the source this was written for, GitHub's REST API,
 * lists commits without their files and charges one request per commit for the files. Splitting
 * them lets {@link ReleaseRecorder} pay that cost only for commits it does not already hold.
 */
public interface CommitHistory {

  /**
   * The most recent commits on {@code main}, newest first, without their changed paths.
   *
   * @return the commits; empty when the source has nothing, never null
   * @throws RuntimeException when the source cannot be read — callers treat that as "try again
   *     later", never as "there is no history"
   */
  List<MainCommit> recent();

  /**
   * The paths one commit touched.
   *
   * @param sha the full commit SHA
   * @return the paths; empty when the commit changed no file
   * @throws RuntimeException when the source cannot be read
   */
  List<String> filesChanged(String sha);
}
