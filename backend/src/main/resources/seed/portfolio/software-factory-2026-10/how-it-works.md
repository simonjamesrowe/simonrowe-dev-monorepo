## What it does

The factory runs on the same Raspberry Pi as this site. It does the chores around a change:
spotting problems, writing them up, fixing the small ones, reviewing every pull request, waiting
for CI and shipping. GitHub Actions does the building and testing.

![The ticket repair loop, from a ticket through checks to a merge]({{media:repair.svg}})

## Finding problems

**Log watch** reads the production logs every night, and again five minutes after every deploy.
Anything new that keeps happening becomes a Linear ticket. If the logs stop arriving, it says so
rather than reporting a quiet night.

**The vulnerability scan** checks every library the site ships against the public advisory
databases. For each backend library with a fix in the same major version, it files a ticket that
says exactly which version to move to. Anything else, including npm packages and major upgrades,
goes into one report for me.

**Static analysis** reads the SonarCloud report on main every night and files new code-quality
problems a few at a time.

## Making the fix

The ticket agent picks up tickets labelled for it. The vulnerability scan labels its own; for
anything else, I add the label. It works in a fresh checkout with file tools only, opens a pull
request, and waits for GitHub Actions. If the build fails, the failure becomes its next brief.
After three attempts it hands the ticket back to me.

## Merging

Every pull request, mine or the agent's, gets an automated review. Every finding has to be fixed,
or answered and resolved, before GitHub will merge. When everything is green, the reviewer turns on
auto-merge for changes that are safe to ship unattended: backend code, docs, and dependency changes
where nothing but version numbers moved. Infrastructure, anything a visitor can see, and anything
that switches a warning off wait for me.

## Shipping

Every merge deploys, whether it's a feature, a bug fix or a security patch. The deployer pulls the
new images, restarts what changed, checks each service, then checks the public site from the
outside. If anything isn't right, it rolls back. Five minutes later, log watch reads the logs to
see whether the release broke anything.

## Learning

When a pull request closes, the factory reads the review. If there's a lesson in it, it opens a
pull request against the agents' instructions, in this repository or in agent-setup, so the same
mistake isn't made twice. Those go through the same review and checks as code, and merge
themselves.
