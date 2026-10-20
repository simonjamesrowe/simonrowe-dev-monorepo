## Three short films of the real factory

The first two films use the production factory with staged demo work. The third revisits a
historical backend vulnerability repair. The log-to-fix ticket describes a
real defect reproduced locally; it is not a manufactured production incident. No private Linear
board is shown. Long agent, build and review waits are cut between real milestones. Narration uses
a synthetic voice.

## 1. A pull request ships itself

<video controls preload="metadata" playsinline poster="{{media:walkthrough-ships-poster.jpg}}" src="{{media:walkthrough-ships.mp4}}" style="width:100%;border-radius:12px"><track kind="captions" src="{{media:walkthrough-ships.vtt}}" srclang="en" label="English"></video>

A tiny documentation pull request passes review and CI, merges under the reviewer's path rules,
and reaches production through Publish and Temporal's verified deployment. A previous guidance
pull request shows how the slower learning loop feeds later reviews.

## 2. An error log becomes a fix

<video controls preload="metadata" playsinline poster="{{media:walkthrough-log-fix-poster.jpg}}" src="{{media:walkthrough-log-fix.mp4}}" style="width:100%;border-radius:12px"><track kind="captions" src="{{media:walkthrough-log-fix.vtt}}" srclang="en" label="English"></video>

From the shape of a logwatch report to a clearly staged ticket for a real timestamp defect: inspect
the dry-run diff, approve the work and follow the repair through GitHub and Linear. The fix handles
invalid metadata and retains its warning. The three-attempt diagram explains what happens when a
real check fails.

## 3. A CVE gets patched

<video controls preload="metadata" playsinline poster="{{media:walkthrough-cve-poster.jpg}}" src="{{media:walkthrough-cve.mp4}}" style="width:100%;border-radius:12px"><track kind="captions" src="{{media:walkthrough-cve.vtt}}" srclang="en" label="English"></video>

This film revisits [PR #138](https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/138),
a real backend vulnerability repair from 30 August 2026. It shows the public advisory, effective
version change, final PR checks, separate merge-commit CI failure and human merge. That repair
predates the current ticket agent. The final diagram explains today's approval and
`no-auto-merge` policy; it is not a replay of the historical repair. npm updates still come back
to a person to regenerate the lockfile.
