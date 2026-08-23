# Contract: the three signal reads

**Feature**: 033-sonarqube-static-analysis

The read contract the `pr-review-loop` skill depends on. Each signal has a
correct read, a wrong read that looks correct, and a defined terminal state.

---

## Signal 1 — CI checks

**Read**

```bash
gh pr checks <pr> --watch
```

**Advisory checks — failure is not blocking**

| Check | Why advisory | Verified at |
| --- | --- | --- |
| `evaluate` (Promptfoo) | `continue-on-error: true` | `.github/workflows/evals.yml:43` |
| `sonar` | `continue-on-error: true` | added by this feature |

**Trap**: `evaluate` is additionally `paths:`-filtered — it only triggers on
chat/agent/retrieval paths. So its normal state on an unrelated pull request is
**absent**, not green. A loop that waits for it to appear waits forever.

**Blocking checks**: `Backend Build & Test`, `Frontend Build & Test`,
`Software Factory Build & Test`.

**Terminal states**: all blocking checks green → proceed. Any blocking check red →
triage and fix.

---

## Signal 2 — reviewer verdict

**Correct read**

```bash
gh api repos/{owner}/{repo}/issues/{pr}/comments \
  --jq '.[] | select(.user.login=="simonrowe-code-reviewer[bot]") | {created_at, body}'
```

**Wrong read that looks correct**

```bash
gh api repos/{owner}/{repo}/pulls/{pr}/reviews   # ← normally EMPTY even on success
```

The reviewer posts an **issue comment**, not a formal review. The reviews list is
empty on a successfully reviewed pull request, so reading it produces a false
"not reviewed yet" that never resolves.

**Trap — silence means failure**: per the existing `code-review-triage` skill, "a
failed review frequently posts nothing to the pull request. Silence is the normal
presentation of failure, not an unusual one." Absence of a comment is **never**
approval.

**Trap — cardinality**: since PR #103 the reviewer posts **one comment per pull
request**, but re-reviews **per pushed commit** (the Temporal workflow id embeds
the head SHA). So iteration count cannot be inferred from comment count, and a
second push does not produce a second comment to wait for.

**Terminal states**:

| Observation | Meaning | Action |
| --- | --- | --- |
| Comment present, no blocking findings | reviewed, clean | proceed |
| Comment present, findings | reviewed, findings | triage |
| Comment says "did not complete" | reviewer failed | hand off to `code-review-triage` |
| No comment after a reasonable wait | reviewer failed | hand off to `code-review-triage` |

---

## Signal 3 — analysis findings and gate

**Reads** — against `https://sonarcloud.io`:

```
GET api/issues/search?componentKeys=simonjamesrowe_simonrowe-dev-monorepo
                     &pullRequest={pr}
                     &resolved=false
GET api/qualitygates/project_status?projectKey=simonjamesrowe_simonrowe-dev-monorepo
                                   &pullRequest={pr}
```

**Authentication**: the repository is public, so these reads should succeed
anonymously. The skill tries unauthenticated first and requests a token **only**
on `401`. A token value is never pasted into chat, echoed, or written to a file.

**Trap — new code only**: `resolved=false` returns everything unresolved on the
pull request branch, which after the first `main` analysis includes accumulated
pre-existing debt. Only findings attributable to this pull request's new code are
in scope. Pre-existing debt is separate work with its own plan.

**Trap — never resolve in the UI**: a finding is either fixed in the diff, or
declined with a stated reason **in the pull request**. Marking it "won't fix" in
Sonar hides the decision from the diff and from review.

**Trap — unverified**: this contract is written against documented API behaviour.
No call here has been executed against a live project, because the project does
not exist yet. First real execution is the operator's first pull request after the
setup checklist. The skill must say so.

**Terminal states**: gate `OK` and no new-code findings → proceed. Gate `ERROR` →
advisory, so it does not block the merge, but the findings are still triaged.
Either read returning `404` → the project or the pull request analysis does not
exist; check the operator checklist rather than retrying.

---

## Cross-signal rules

| ID | Rule |
| --- | --- |
| X-1 | All three signals are waited on, not just CI. |
| X-2 | Each signal's absence has a defined meaning; none defaults to "pass". |
| X-3 | A questionable finding is verified before being obeyed — defers to `superpowers:receiving-code-review`. |
| X-4 | The loop is bounded at roughly three fix-and-push iterations, then stops and reports. |
| X-5 | The final report states: PR URL, CI state, findings addressed, findings declined with reasons, gate status. |
