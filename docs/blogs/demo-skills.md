I'm rubbish at recording demos. I do six takes, fluff a click on the fifth, mumble the bit that
mattered, and end up with four minutes of me hunting for a button. So this week I stopped doing
them myself and built two Claude Code skills to do it for me.

The results are on the site now. [Term Time](/portfolio/term-time), the school assistant I built
for our local primary school, has a two-minute walkthrough. So does
[Clinician's Veil](/portfolio/clinicians-veil), a Mac app that takes the patient's identity out
of a clinical note before a frontier model writes the letter. Same voice, same hand-drawn
diagrams, same end card, and nobody held a mouse for either of them.

[![The Term Time demo, paused on the Year 6 residential answer with its source link highlighted.](/uploads/a877cd36-ac56-4550-88fb-07843968f6f4/original.webp)](/portfolio/term-time)
*The Term Time demo. Click through to watch it on the project page.*

The idea came from Dan Vega's post on
[Claude Code skills that aren't for coding](https://www.danvega.dev/blog/claude-code-skills-not-just-for-coding),
where he chains small skills together to put out a podcast. The part I pinched was the shape:
small skills, one job each, and a person signing off before anything goes out. This post is how
mine work, and the bits that bit me.

## A demo is two jobs

The thing I got wrong for years is treating a demo as a recording problem. It isn't. It's a story
problem with a recording at the end. If you don't know the one thing a viewer should believe
afterwards, no amount of nice cursor animation will save you.

So there are two skills:

- **`demo-plan`** works out the story and writes the script. It doesn't record anything.
- **`demo-record`** turns an approved script into the video. It doesn't get to change the story.

Between them sits the rule I care about most. Nothing gets recorded until I've approved the plan,
scene by scene, in the conversation. Re-recording is cheap. Narration in my name that I never
agreed to isn't.

![The pipeline. demo-plan grills me for the aim, walks the product, drafts and rehearses the script, and stops at my approval of the plan table. demo-record then narrates, films the browser or the Mac app with diagram walkthroughs, mixes, verifies every still, and produces the MP4, captions, poster and blurb. A dashed loop runs from verify back to the script: tweak a line, rebuild.](/uploads/9f36b50c-f5eb-4d4a-80a2-d948c9268db9/original.svg)
*Story first, video second, and a human gate in between.*

Both skills live in my [`agent-setup`](https://github.com/simonjamesrowe/agent-setup) repository,
next to the rest of the skills I use with Claude Code, Codex and Gemini CLI. The first version
went in on 29 September. Both demos were done by 3 October.

## demo-plan: getting grilled

The first step is an interview, and it's annoying on purpose. It uses the grilling skill from
[Matt Pocock's skills](https://github.com/mattpocock/skills): ask every question it can't answer
itself, wait, then ask the next round. "Show what it does" isn't an aim, and it'll tell you so.

It wants, roughly in this order:

- **The product** and where it runs: production, or the local stack if the demo changes data.
- **The audience.** For both of these it was the people who'd use it first, then engineers.
- **The aim**, in one sentence. For Clinician's Veil: *a frontier model can draft a clinical
  letter without the patient's identity leaving the Mac.*
- **No more than three key messages**, each tied to something you can see on screen. If a message
  has no moment on screen, it gets cut.
- **The moment.** The one interaction worth watching, about two-thirds of the way through.
- **What's out.** Clinician's Veil has dictation, search and a costs panel. None of them made the
  cut, and the video's better for it.
- **The outro**, exactly two sentences: what was demoed, then why it matters.

That last one sounds fussy, but it does the most work. Those two sentences become the end card,
the last caption and the blurb on the project page, so I write them with the agent, not leave
them to it. Here's Clinician's Veil's. It took a round of edits before the second sentence
said something I'd actually stand behind:

> That was Clinician's Veil, a Mac app that de-identifies clinical notes before any AI sees them.
> The model writes the letter; the patient's identity never leaves the Mac.

Once the brief's done, the agent uses the Playwright MCP server to go through the real product
the way the demo will. It notes the accessible name of every button it touches, how long each
step takes, and anything that's broken. A demo of a broken page isn't worth recording, so if it
finds one it stops and tells me.

Then it drafts `script.mjs`. It's a plain object of scenes, and each one has what's on screen
(`show`), what I say (`say`) and what happens (`do`). It rehearses the actions headless to time
them, and turns all that into a plan table. The table is the thing I approve. Here's the top of
Term Time's:

| # | Scene | Narration | Est. |
| --- | --- | --- | --- |
| 1 | `hook` | A primary school sends a lot. Newsletters, letters, calendar changes and class emails, plus whatever turns up in the parents' WhatsApp group. Term Time reads all of it, so I can just ask. | ~13s |
| 2 | `half-term` | When is half term? It checks today's date, then looks the term dates up in the school's own calendar feed. | ~8s |
| 3 | `source` | Every answer carries its source. That link opens the calendar entry it came from, so I can check it in one tap. | ~9s |

Reading the narration in a table is heaps quicker than watching a video to find the line that's
off. Most of my edits happen here, before there's a single frame. Term Time's biggest edit was a
cut: there was a scene about approving school emails, and I took it out. It was true, it just
wasn't one of the three messages.

## demo-record: the voice goes first

The recorder is one Node script, and the decision that makes it work is the opposite of how you'd
do it by hand. **It voices every line before it opens the browser.**

Each scene's narration goes through Google Cloud text-to-speech first, so the recorder knows
exactly how long I'm going to talk. Then it plays the scene, holds it for whichever's longer (the
action or the voice-over), and drops each clip into the mix at the moment its scene actually
started. There's no re-timing in an editor and no dragging audio around. Clips are cached on a
hash of the text and voice, so changing one line only re-voices that line.

The voice is the one the site already uses to read blog posts aloud, an Australian one called
`en-AU-Chirp3-HD-Achird`. It's pinned in the script rather than read from config, because my
local env file still had a British voice in it and the first demo would've come out sounding like
someone else. They're narrating as me, so they get the accent.

A few other choices that mattered:

- **The frames come from Chromium's screencast**, not Playwright's built-in recorder. The
  built-in one saves at about 1 Mbps, which blurs small text. That's no good when the point of the
  scene is reading an answer.
- **There's a visible cursor, and it glides.** Headless browsers don't draw one, and a plain
  `click()` jumps straight there, so the viewer can't follow what happened.
- **It all plays in one tab.** Links that would open a new tab load in place instead. I asked for
  that one because the software factory demo will go from Linear to GitHub to the site and back.
- **Setup is cut out.** Signing in, dismissing banners and clearing saved state happen before the
  clock starts.
- **AI answers change between takes**, so a scene waits for the thing that proves the answer has
  arrived. In Term Time, that's the typing indicator going away and a source link appearing. The
  narration talks over the wait so it's never a silent spinner.

Then ffmpeg mixes it all down to −16 LUFS with `+faststart`, writes WebVTT captions and picks a
poster frame. The captions come from the written narration, not the spoken one. So when the voice
needs help, like `'GPT-6 Sol': 'G P T six Sol'`, the captions still spell it properly.

It also logs every 4xx, 5xx and console error during the take. That paid for itself on the very
first test run. Every uploaded image on simonrowe.dev was coming back 502, and the recorder flagged it
before I'd watched a frame.

## Hand-drawn diagrams, talked through

Every demo ends with a "here's how it fits together" bit. I wanted it to feel like someone
sketching on a whiteboard, not a slide.

A diagram is a list of Excalidraw shapes in `diagrams/<name>.json`. The renderer adds the house
style: the Excalifont handwriting font, rough lines and cross-hatched pastel fills. Then a scene
calls `diagram.focus('ingest')`. That fades everything else back, sketches an amber outline round
the section and moves the camera onto it while I talk about it. Each section matches one of the
brief's key messages.

This is where I found my first proper bug. Term Time was recorded at double resolution for
sharper text, and the amber outlines were drawn a little to one side of the boxes they were meant
to highlight. My review on first watch was "the boxes that were highlighted don't actually line
up with what's on the diagram", and the fix was re-recording at normal resolution. The diagram at
the top of this post came out of the same renderer. Seemed rude not to.

## The Mac app was the hard one

Term Time is a website, which is the easy case. Clinician's Veil is a Tauri app on my Mac, and the
whole point is showing that the patient's details stay on the laptop. Tauri's WebDriver tooling
doesn't support macOS, so the recorder grew a native mode.

A small Swift helper drives the real app through macOS's Accessibility tree and films that part of
the screen with ScreenCaptureKit. Meanwhile the browser stays headless for the diagrams and the
end card, and the two get cut together wherever the picture switches. Targets look like
Playwright's (`{ role: 'button', name: 'Find identifiers' }`), so a native scene reads the same as
a web one.

The catch is that it's the real pointer on the real screen, and I learnt that the hard way.
Partway through a rehearsal I opened another window over the top of the app, and the next few
keystrokes, a synthetic patient's name and half a case number, went straight into it. Fair
enough, that's on me. The helper now checks which app owns the focused field before every click,
keystroke and scroll, and stops the take if the mouse moves on its own. Do Not Disturb goes on
too, because a notification over the window ends up in the video.

Permissions were the other fun bit. macOS didn't give Screen Recording to Conductor, the app I
run my agents in. It gave it to the `claude` binary inside it. Two relaunches of Conductor did
nothing, and it took a system call to find which process the permission actually belonged to.

Every take sends a real request to GPT-6 Sol, which costs about five cents. Three of the four
builds failed before one went all the way through. One failed because the letter came back worded
differently and a highlight couldn't find its phrase. One hit a "replace this file?" dialog from a
PDF left over from the last take. One failed because the app was relaunched while it was still
quitting. There's now a `prepare.sh` that resets the library and cleans up before every take.

That demo also shows why the brief matters. The local models flagged the word "SYNTHETIC" from
the test-data banner as an organisation, and missed the case reference and the house number. I
could have scripted round that. Instead it's in the video: I keep the false positive, select the
two misses and add them by hand. The clinician deciding is the product, so it'd be daft to hide
it.

## If you're building your own

- **Write the outro first.** If you can't say what was demoed and why it matters in two
  sentences, you're not ready to record.
- **Approve the words, not the video.** A table of narration is quicker to review than two
  minutes of footage, and it's where the real edits happen.
- **Look at every still.** The build saves one frame per scene, plus the end card. A spinner, an
  empty state or a cursor parked over the answer is easy to miss at full speed and obvious in a
  still.
- **Treat page errors as blockers.** A broken image in a published demo is worse than no demo.
- **Record anything that changes data against the local stack.** Term Time's chat scenes run on
  production because they don't change anything. Pasting a note does, so those scenes run on my
  laptop against last night's backup.
- **Keep captions off by default.** On a screen recording they cover the thing you're showing. I
  found that out from my own first viewing.

All up, Term Time took ten builds to get from 2:12 down to 1:53, and Clinician's Veil came in at
2:20. Neither number is the point. The point is that the eleventh build would take one command,
and I'd only be changing words.

The honest caveat is that these skills are tuned to me: my voice, my site, my house style, my
sign-off. That's fine. A skill is just the judgement I'd otherwise have to explain again every
session, written down where the next one can find it. Next up is the software factory, and the
first thing the skill will ask me is what I want people to believe afterwards.
