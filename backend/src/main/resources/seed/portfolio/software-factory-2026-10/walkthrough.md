## Films of the real factory

These are real runs on the production factory and real pull requests. Waits are cut. The voice is
synthetic.

## It learns from its reviews

<video controls preload="metadata" playsinline poster="{{media:learning-poster.jpg}}" src="{{media:learning.mp4}}" style="width:100%;border-radius:12px"><track kind="captions" src="{{media:learning.vtt}}" srclang="en" label="English"></video>

The reviewer caught a critical bug in [PR #161](https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/161):
a dry run of the log scan would still have closed real tickets. I fixed it. When the pull request
closed, the factory opened [PR #164](https://github.com/simonjamesrowe/simonrowe-dev-monorepo/pull/164)
to write the lesson into the instructions the agents read, and the same rule is now in
[agent-setup](https://github.com/simonjamesrowe/agent-setup).

## A vulnerability fixes itself

Next: a real backend vulnerability going from the nightly scan to production with nobody involved.
