## Two containers, different authority

![Software Factory containers, Temporal queues and external systems]({{media:architecture.svg}})

`software-factory` is a Spring Boot service containing the signed webhook receiver and Temporal
workers. Git and Claude Code are baked into its image. It holds the GitHub, Claude, Linear and
other credentials its enabled modules need. nginx exposes one exact public route to it:
`POST /webhooks/github`. The factory checks the HMAC signature before accepting the event.

`deployer` runs the same image with a different role and module flags. It has no public ingress
and holds the Docker socket. Container recreation is its responsibility; the public backend and
the code-editing factory do not receive that host-level access.

## Temporal owns the waits

A role has its own task queue: `code-review`, `review-feedback`, `cve-fix`, `logwatch`,
`ticket-agent`, `linear`, `deploy` and `platform-backup`. Linear is an activity-only sink rather
than a workflow poller. These are roles inside the shared image, not eight separately deployed
agent services.

Workflow history records decisions, activity results and waits. A ticket run can sleep for CI,
read the actual checks back from GitHub, repair a rejection, then wait for the merge. Signals wake
it; they do not replace reading the authoritative check and pull-request state. Bounded fallback
reads prevent a lost webhook from leaving the ticket asleep forever.

## The Pi is the design constraint

![File edits on the Pi and builds on GitHub Actions]({{media:constraint.svg}})

The host is a Raspberry Pi 5: ARM64, 16 GB, already sharing its resources across the production
stack, with the memory cgroup off. A build would also need dependency downloads and the
Docker-backed test infrastructure. The ticket agent therefore gets file tools — Read, Glob,
Grep, Edit and Write — and no Bash, web or MCP tools. It cannot build, test or download dependencies.
GitHub Actions is the build machine.

That boundary applies to the ticket agent. The surrounding factory still runs its own controlled
git and Claude processes, and the separate deployer runs deployment commands.

## Controls on proposals and merges

The ticket and failure brief are untrusted data. Their prompt blocks are fenced, and closing tags
inside them are broken so quoted text cannot close its own block. The child process receives an
allowlisted environment. Java checks changed paths before pushing: secrets, infrastructure,
workflow configuration, scripts, Dockerfiles and the Gradle wrapper remain denied.

Silencing a warning, disabling a test or suppressing a finding requires a person's merge.
Build and dependency files are pushable but also require a person's merge. The full PR file list,
including rename sources and earlier repair attempts, determines that dependency guard.

There is an accepted limit to this control: CI executes an edited build script with the PR's
repository secrets, including the analysis token, before human review. Human approval and a human
merge do not prevent that earlier execution. The tradeoff is explicit in the runbook, alongside
the low value of that token and the remaining npm lockfile gap.
