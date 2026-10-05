package com.simonrowe.factoryadmin;

import java.time.Instant;

/**
 * The commit one deployed service was built from.
 *
 * @param service the service name
 * @param commit the full commit SHA
 * @param commitTime when that commit reached {@code main}
 */
public record ServiceCommit(String service, String commit, Instant commitTime) {
}
