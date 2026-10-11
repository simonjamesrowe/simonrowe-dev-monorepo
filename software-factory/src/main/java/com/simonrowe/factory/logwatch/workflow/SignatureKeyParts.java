package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import java.util.List;

/**
 * The key parts a signature's Linear fingerprint is computed from, built in exactly one place.
 *
 * <p>Two callers need them and they must agree to the byte: filing, which advances a
 * fingerprint's {@code lastSeenAt}, and {@code observe}, which reports the signatures the per-run
 * cap dropped so the absence sweep can leave their tickets open. If the two ever built key parts
 * differently, a dropped signature would hash to a fingerprint no ticket carries, protect nothing,
 * and the sweep would close the ticket of a problem this very scan saw — the exact failure the
 * cap veto used to prevent by switching the sweep off altogether.
 */
final class SignatureKeyParts {

  private SignatureKeyParts() {
  }

  /**
   * The key parts for one signature.
   *
   * <p>The source key, never the generated title and — since 046 — never the whole normalised
   * line either. Both are phrasings of the problem, and a phrasing that varies files a second
   * ticket: three phrasings from one Embabel logger became SIM-13, SIM-24 and SIM-25 for one
   * startup failure. Severity is explicit here rather than left implicit inside the message text.
   *
   * @param signature the grouped problem
   * @return {@code [container, severity, sourceKey]}
   */
  static List<String> of(final LogSignature signature) {
    return List.of(signature.container(), signature.severity().name(), signature.sourceKey());
  }
}
