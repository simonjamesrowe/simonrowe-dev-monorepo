# Feature Specification: Platform Datastore Backup

**Feature Branch**: `034-platform-datastore-backup`

**Created**: 2026-08-25

**Status**: Draft

**Input**: User description: "Platform datastore backup (Postgres + ClickHouse) — implement the approved design at docs/superpowers/specs/2026-08-25-platform-datastore-backup-design.md: nightly + on-demand backup of the four Postgres databases (langfuse, dtrack, temporal, temporal_visibility) and the ClickHouse default database, uploaded to a separate Google Drive folder with retention of 7, plus a host restore shell script and runbook."

## Overview

The site's nightly backup protects the application's own data — site content, media
and search embeddings — and nothing else. The supporting platform that has grown up
around the application is entirely unprotected: the vulnerability findings, the AI
observability history, and the workflow history all live in two datastores that no
backup touches. A single disk failure loses all of it permanently.

This feature extends backup coverage to those platform datastores and provides a
documented, rehearsed way to get them back onto a new or rebuilt host.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Platform data is captured every night without anyone thinking about it (Priority: P1)

The site operator wants the platform's supporting datastores captured on the same
nightly rhythm as the application data, uploaded off-host, with only a bounded
number of copies kept so storage does not grow without limit. They should not have
to remember to do anything.

**Why this priority**: This is the whole point of the feature. Until a nightly
capture exists and is retained off-host, every other part of this work protects
nothing. It is also independently valuable: even with no restore tooling at all,
having the data off the host converts "permanent loss" into "recoverable with
effort".

**Independent Test**: Let the scheduled time pass (or set the schedule to a near
time) on an environment with off-host storage connected, then confirm a new archive
appears in the platform storage location containing all five expected datastore
dumps, and that only the newest 7 archives remain.

**Acceptance Scenarios**:

1. **Given** the platform is running and off-host storage is connected, **When** the
   nightly time arrives, **Then** a single dated archive containing all four
   relational databases plus the analytics database is uploaded to the platform
   storage location.
2. **Given** 7 platform archives already exist off-host, **When** an eighth is
   uploaded successfully, **Then** the oldest is removed and exactly 7 remain.
3. **Given** off-host storage is not connected, **When** the nightly time arrives,
   **Then** the run is skipped with a clear warning and nothing fails.
4. **Given** another data operation is already running, **When** the nightly time
   arrives, **Then** the platform backup is skipped with a logged message and no
   partial archive is left behind.
5. **Given** a database dump fails partway through, **When** the run ends, **Then**
   the run is reported as failed, no archive is uploaded, no removal of existing
   archives happens, and no temporary files are left on disk.
6. **Given** the existing application-data backup, **When** platform backups are
   introduced, **Then** the application backup's schedule, storage location and
   number of retained copies are all unchanged.

---

### User Story 2 - The operator can take a platform backup on demand before a risky change (Priority: P2)

Before upgrading one of the platform tools or performing maintenance, the operator
wants to capture the platform datastores immediately rather than relying on last
night's copy, and to see at a glance that the nightly job is actually running.

**Why this priority**: Valuable but not protective on its own — the nightly capture
already gives a recovery point. This story makes the capability visible and gives
the operator control at the moment of risk, which is when it matters most.

**Independent Test**: From the administration area, trigger a platform backup and
watch it progress to completion, then confirm the new archive appears in the listed
backups with its date and size.

**Acceptance Scenarios**:

1. **Given** an authenticated administrator on the data operations page, **When**
   they trigger a platform backup, **Then** progress is reported live and completion
   or failure is stated explicitly.
2. **Given** an authenticated administrator on the data operations page, **When**
   the page loads, **Then** the retained platform backups are listed with date and
   size, making a stalled nightly job obvious.
3. **Given** a request from someone who is not an authenticated administrator,
   **When** they attempt to trigger a platform backup or list platform backups,
   **Then** the request is rejected.
4. **Given** a platform backup is already running, **When** a second trigger
   arrives, **Then** it is refused rather than running two captures at once.

---

### User Story 3 - The operator can restore one platform tool onto a live or rebuilt host (Priority: P2)

After a host failure, or after one tool's data is corrupted, the operator wants to
restore that tool's data from a chosen archive — targeting one tool at a time,
without disturbing the others — and to be stopped before doing something
irreversible and wrong.

**Why this priority**: A backup nobody can restore is not a backup, so this is
essential to the feature's purpose. It ranks below the capture itself only because
capture must exist first: data captured today can be restored by a script written
tomorrow, but data never captured is gone.

**Independent Test**: On a local environment, list the available archives, perform a
rehearsal that prints every action without performing any, then perform a real
restore of one tool and confirm that tool's data is present and the other tools are
untouched and still running.

**Acceptance Scenarios**:

1. **Given** an archive, **When** the operator asks to see what a restore would do,
   **Then** every action is printed and nothing is changed.
2. **Given** an archive, **When** the operator restores one named tool, **Then** only
   that tool's databases are replaced and only that tool's dependent services are
   stopped and restarted.
3. **Given** an archive whose recorded secret fingerprints do not match the host's
   current secrets, **When** a restore is attempted, **Then** it is refused with an
   explanation, and it proceeds only if the operator explicitly overrides.
4. **Given** a restore that fails partway, **When** it aborts, **Then** the services
   it stopped are restarted so they run against the pre-restore data rather than
   being left down.
5. **Given** a restore, **When** it begins, **Then** a local copy of the data about
   to be overwritten is taken first.
6. **Given** a restore of one tool fails, **When** it aborts, **Then** the other
   tools' data and availability are unaffected.
7. **Given** a rebuilt host with no local archive, **When** the operator asks for the
   latest, **Then** the newest archive is fetched from off-host storage using
   credentials already present on the host.

---

### Edge Cases

- **Residue from crashed earlier runs.** A run that dies after producing an
  intermediate analytics export leaves that file on the host. Repeated over several
  nights this silently consumes the host's storage card. Each run must clear such
  orphans before it starts.
- **Sharing a storage folder with application backups.** Retention keeps the newest
  N archives in a folder. If both backup types shared one folder they would evict
  each other, quietly halving each type's recovery window. They must not share.
- **A restored database whose secrets do not match.** Two of the platform tools
  encrypt stored values with host secrets. Restored onto a host with different
  secrets, the rows load without error and then fail to decrypt — a failure that
  presents as success. The archive must record enough to detect this and refuse.
- **Successful upload, failed cleanup of old archives.** Reporting this as a backup
  failure would send the operator hunting for lost data that is safely stored. It
  must be reported distinctly.
- **Overrun of the application backup into the platform window.** The application
  backup uploads all media over a residential uplink and can run long. The two must
  be scheduled far enough apart that this is rare, and a collision must skip
  cleanly, log visibly, and self-correct the following night.
- **Analytics data captured slightly after relational data.** A handful of records
  captured in the gap may reference rows created just after the relational capture.
  This is accepted: the affected rows are near-immutable configuration.
- **A restore target's tables already exist.** Restoring the analytics database over
  a populated one must be handled explicitly rather than silently half-merging.
- **Unbounded analytics growth.** Nothing currently expires the trace history, so
  archive size grows without limit. The size must be measured before rollout so the
  operator knows whether storage quota is at risk.

## Requirements *(mandatory)*

### Functional Requirements

#### Capture

- **FR-001**: The system MUST capture all four platform relational databases —
  observability, vulnerability tracking, workflow history, and workflow visibility —
  as individually restorable dumps within a single archive.
- **FR-002**: The system MUST capture the analytics database (traces, observations
  and scores) in a form that the analytics engine itself can restore, so that the
  capture stays correct across platform tool version upgrades without anyone
  maintaining a list of tables.
- **FR-003**: The system MUST capture the database role definitions separately from
  the per-database dumps, so a restore can create absent roles without disturbing
  existing ones.
- **FR-004**: The system MUST produce exactly one dated archive per run.
- **FR-005**: Each archive MUST include a manifest recording: archive format version,
  capture time, per-database dump sizes, analytics per-table row counts, the version
  of each platform tool at capture time, and a one-way fingerprint of each relevant
  host secret.
- **FR-006**: The manifest MUST NOT contain any secret value, only fingerprints from
  which the value cannot be recovered.
- **FR-007**: The archive MUST NOT contain the host's secrets file.
- **FR-008**: The system MUST remove intermediate and local files at the end of every
  run, on both the success and failure paths, and MUST clear orphans left by earlier
  crashed runs at the start of a run.

#### Scheduling and storage

- **FR-009**: The system MUST run the platform capture automatically once per night,
  at a configurable time, in a configurable time zone.
- **FR-010**: The nightly time MUST be scheduled with a wide enough margin from the
  application backup that an overrunning application backup rarely collides with it.
- **FR-011**: The system MUST upload each archive to off-host storage, in a location
  separate from the one used by application backups.
- **FR-012**: The system MUST retain the newest 7 platform archives, and the count
  MUST be configurable.
- **FR-013**: Retention cleanup MUST run only after a successful upload.
- **FR-014**: The system MUST leave the application backup's schedule, storage
  location and retained count exactly as they are.
- **FR-015**: The system MUST skip the nightly run, with a clear warning and no
  failure, when off-host storage is not connected.
- **FR-016**: The system MUST skip the nightly run when another data operation is in
  progress, and MUST NOT retry within the same night.
- **FR-017**: A failure in the nightly run MUST NOT propagate out of the scheduling
  mechanism in a way that could stop future runs.
- **FR-018**: A cleanup failure following a successful upload MUST be reported as a
  cleanup failure, distinctly from a capture failure.
- **FR-019**: A failure to remove one old archive MUST be logged without aborting
  removal of the remaining ones.

#### On-demand use

- **FR-020**: An authenticated administrator MUST be able to trigger a platform
  capture immediately.
- **FR-021**: An authenticated administrator MUST be able to list the retained
  platform archives with their date and size.
- **FR-022**: Both capabilities MUST reject unauthenticated and non-administrator
  requests.
- **FR-023**: An on-demand capture MUST report live progress and a definite final
  outcome to the administrator.
- **FR-024**: At most one data operation MUST run at a time; a trigger arriving while
  another operation runs MUST be refused rather than queued or run concurrently.
- **FR-025**: The administration interface MUST present platform backups as their own
  distinct area, showing the trigger and the retained archive list.

#### Restore

- **FR-026**: Restore MUST be operable without the application running, so that it
  works on a host being rebuilt.
- **FR-027**: Restore MUST be selectable per tool — observability, vulnerability
  tracking, workflow history, or all — so that restoring one cannot affect the
  others.
- **FR-028**: Restore MUST be able to use either a local archive or the newest
  archive fetched from off-host storage, using credentials already present on the
  host.
- **FR-029**: Restore MUST be able to list the archives available off-host.
- **FR-030**: Restore MUST offer a rehearsal mode that prints every action it would
  take and changes nothing.
- **FR-031**: Restore MUST verify the archive's secret fingerprints against the
  host's current secrets and refuse to proceed on mismatch unless explicitly
  overridden.
- **FR-032**: Restore MUST capture a local copy of the data it is about to overwrite
  before overwriting it.
- **FR-033**: Restore MUST stop the services that consume a target's database before
  replacing it, and terminate any connections that remain.
- **FR-034**: Restore MUST create absent database roles from the archive without
  altering roles that already exist.
- **FR-035**: Restore MUST restart every service it stopped, including when the
  restore fails, so a failure leaves services running against the pre-restore data
  rather than stopped.
- **FR-036**: Restore MUST confirm that restarted services return to health before
  reporting success.
- **FR-037**: Restoring the analytics database over an existing populated one MUST be
  handled explicitly and verified against the deployed analytics engine version, not
  assumed.

#### Documentation

- **FR-038**: A runbook MUST document: how to confirm the nightly capture ran, how to
  restore a single tool onto a running host, and the correct ordering for
  cold-starting a rebuilt host.
- **FR-039**: The deployment note MUST record that rolling this out restarts the
  analytics engine and the application, so it can be combined with other pending
  maintenance.

### Non-Functional Requirements

- **NFR-001**: The capture MUST NOT require any new host-level prerequisite —
  no new installed package, service unit, or manual setup step beyond deploying
  the change.
- **NFR-002**: The capture MUST NOT require any new secret to be provisioned or
  distributed.
- **NFR-003**: Uploads MUST be resumable and MUST achieve the same throughput as the
  existing application backup on the same uplink.
- **NFR-004**: The archive size MUST be measured on the production host before
  rollout, and the result assessed against available off-host storage quota.

### Out of Scope

- Backing up the object store holding large trace payload bodies, the cache, the
  message log, the container-management settings, or the self-rebuilding search
  index for vulnerability data.
- Any in-application restore capability.
- Any change to the application-data backup.
- Configuring expiry on the analytics trace tables. Likely wanted, and possibly
  necessitated by NFR-004, but a separate change.

### Key Entities

- **Platform archive**: One dated, self-contained file per capture. Holds a manifest,
  one dump per relational database, one role-definitions dump, and one analytics
  export.
- **Manifest**: The archive's self-description — format version, capture time, dump
  sizes, analytics row counts, tool versions at capture time, and secret
  fingerprints. What makes an archive interpretable and safely restorable months
  later.
- **Secret fingerprint**: A one-way digest of a host secret, used solely to detect
  that an archive was captured under different secrets than the host now has.
- **Restore target**: A named group of one or more databases plus the services that
  consume them — the unit of restore, chosen so targets are independent.
- **Retention window**: The newest 7 archives in the platform storage location, held
  independently of the application backups' own window.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every platform datastore currently unprotected — the four relational
  databases and the analytics database — is present in the nightly archive; zero
  remain uncovered.
- **SC-002**: A platform archive is created and uploaded automatically every night
  without operator action, verifiable from the archive list's dates.
- **SC-003**: Exactly 7 platform archives are retained; the count neither grows
  without bound nor drops below 7 while nightly runs succeed.
- **SC-004**: The application backup's retained count remains 7 after platform
  backups are introduced — the two backup types never evict each other.
- **SC-005**: An operator can trigger a platform backup and see its outcome without
  leaving the administration interface and without shell access.
- **SC-006**: An operator can determine whether the nightly job is healthy in under
  one minute, from the archive list alone.
- **SC-007**: A restore of any single tool has been performed successfully at least
  once against a non-production environment, and the outcome is recorded in the
  runbook — the analytics restore path in particular is proven, not assumed.
- **SC-008**: A restore attempted against mismatched host secrets is refused 100% of
  the time unless explicitly overridden — the silent-corruption path cannot be
  entered by accident.
- **SC-009**: A failed restore leaves every service it stopped running again, so
  availability after a failed restore equals availability before it.
- **SC-010**: A failed capture uploads nothing, deletes nothing already stored, and
  leaves no files behind on the host.
- **SC-011**: Rolling this out requires no manual host setup beyond the deployment
  itself.
- **SC-012**: The archive's size on the production host is measured and recorded
  before rollout, so the storage-quota risk is known rather than discovered.

## Assumptions

- The existing off-host storage integration, its progress reporting, its operation
  mutex and its retention mechanism are reused rather than reimplemented; only
  retention needs generalising to address a second storage location.
- The credentials needed to read the platform datastores are already available to the
  component that will perform the capture, so no new secrets plumbing is required.
- The component performing the capture can already orchestrate the platform's
  containers — this capability exists and is used today for redeployment.
- Accepting that a wedged application means no platform capture that night is
  reasonable: it is already true of the application backup, an unhealthy application
  is automatically restarted within a minute, and a dead host defeats every capture
  mechanism equally.
- Restoring a tool onto a host whose secrets differ is treated as operator error to
  be blocked, not a scenario to support: re-encrypting stored values under new
  secrets is out of scope.
- Restored trace records will be missing very large payload bodies, because those
  live in the out-of-scope object store. This is accepted and must be stated in the
  runbook so it is not discovered during an incident.
- The cache, message log and build workspace are genuinely disposable, and the
  vulnerability search index rebuilds itself, so their exclusion loses nothing that
  cannot be regenerated.
- Retention of 7 matches the existing application backup's window, chosen for
  consistency rather than from a separate recovery-point requirement.
- The archive format is versioned from the outset so a future format change can be
  detected by a restore rather than misread.
