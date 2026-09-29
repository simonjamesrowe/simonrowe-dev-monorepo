package com.simonrowe.factory.logwatch.workflow;

import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.SourceHealth;
import java.util.List;

/**
 * Everything one read of the log source produced, as a single activity result.
 *
 * <p>Read, grouping and the source-health verdict travel together because they are one
 * observation: splitting them across activities would let a retry re-read the window and reach a
 * different verdict than the one the signatures came from.
 *
 * @param sourceHealth whether these results mean anything
 * @param signatures the grouped problems, already filtered and capped
 * @param linesRead how many lines were read
 * @param truncated whether the read hit its line budget, so an unknown number of further lines
 *     exist that this scan never examined. A count is deliberately not reported: Loki's response
 *     is capped at the limit and carries no total, so any number here would be invented. What
 *     FR-006 requires is that a truncated read is never presented as a complete one.
 * @param containersSeen how many distinct containers produced lines
 * @param signaturesDropped how many signatures the per-run cap discarded
 * @param mutedSignatures how many signatures a {@code factory.logwatch.ignore} rule withheld.
 *     Counted separately from {@code signaturesDropped} and never folded into it: a dropped
 *     signature is one this run saw but could not fit — which is why the absence sweep treats it
 *     as present — while a muted one is a group the configuration says is somebody else's,
 *     deliberately, every run, and whose ticket the sweep is <em>meant</em> to close. Collapsing
 *     the two would keep every muted ticket open for ever
 * @param mutedBy the {@code reason} of each rule that muted something, most-muted first, so a
 *     run that withheld findings says which rules did it rather than merely how many
 * @param droppedKeyParts the fingerprint key parts of each signature the per-run cap discarded,
 *     one entry per dropped signature, built by {@link SignatureKeyParts} exactly as filing builds
 *     them. The cap limits what is <em>filed</em>, not what was <em>seen</em>, and this is what
 *     lets the absence sweep leave exactly those tickets open instead of refusing to run at all.
 *     Its size is expected to equal {@code signaturesDropped}; a result recorded by a build that
 *     predates the field deserializes it as empty, and the sweep reads any shortfall as "the
 *     dropped signatures are unknown" and keeps the old all-or-nothing veto
 */
public record ScanObservation(
    SourceHealth sourceHealth,
    List<LogSignature> signatures,
    int linesRead,
    boolean truncated,
    int containersSeen,
    int signaturesDropped,
    int mutedSignatures,
    List<String> mutedBy,
    List<List<String>> droppedKeyParts) {

  public ScanObservation {
    signatures = signatures == null ? List.of() : List.copyOf(signatures);
    mutedBy = mutedBy == null ? List.of() : List.copyOf(mutedBy);
    droppedKeyParts =
        droppedKeyParts == null
            ? List.of()
            : droppedKeyParts.stream().map(List::copyOf).toList();
  }
}
