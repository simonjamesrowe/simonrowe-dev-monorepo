## Small loops, with evidence at every handover

The factory runs on the same Raspberry Pi as this site. It does the repetitive work around a
change: noticing a problem, describing it, reviewing a pull request, watching the checks and
shipping the result. The ticket agent works on small, approved repairs. GitHub Actions builds
and tests them away from the Pi.

![The ticket repair loop, from approval through checks and a merge]({{media:repair.svg}})

## A pull request ships

A signed GitHub webhook starts a review of an exact commit. The reviewer posts its check,
summary and inline findings. CI must pass, and every review conversation must be resolved by
fixing the finding or explaining why it does not apply.

The reviewer can arm auto-merge for eligible paths. Deployment configuration and other sensitive
changes need a person. A merge starts Publish on GitHub Actions; a successful Publish starts the
Temporal deployment workflow. The deployer pulls and recreates the allowed services, verifies
them, and checks the public site after maintenance comes down. When enabled, a log scan follows
five minutes after a successful deploy.

## An error becomes a ticket

Logwatch reads the production logs in Loki, groups recurring errors and warnings by their
emitting source and files a Linear ticket. Its source-health checks distinguish an absence of
errors from an absence of usable logs. A quiet pipeline must not masquerade as a healthy site.

A person chooses whether to hand a ticket to the agent. A dry run posts the proposed diff as a
ticket comment, without pushing a branch or changing its state. Starting a real run applies
`factory:agent` and moves the ticket to In Progress.

The agent edits files and opens a pull request. GitHub Actions and the reviewer judge the exact
commit. A red check becomes the next repair brief, with three attempts in all. Work it cannot
complete comes back to a person. Green checks move the ticket to In Review; an actual merge moves
it to Done.

## A vulnerability becomes a scoped repair

![Current vulnerability workflow, with human approval and merge]({{media:cve.svg}})

The vulnerability scanner reads Dependency-Track and creates or updates one consolidated report
for the repository. It changes no code. A person chooses a backend finding and approves a scoped
repair; the ticket agent can then propose a catalogue or build-script version update.

Every build or dependency change carries `no-auto-merge`. CI verifies it, and a person merges it.
An npm bump still needs a person to regenerate the lockfile: this agent has no npm, so retries
cannot manufacture its integrity hashes. Backend catalogue and constraint changes need no lockfile.

## Lessons feed the next review

Review conversations from closed pull requests are distilled into proposed guidance. Guidance
changes have their own review and merge policy. The loop improves the instructions reviewers use,
without treating a model's summary as a reason to skip the repository's checks.
