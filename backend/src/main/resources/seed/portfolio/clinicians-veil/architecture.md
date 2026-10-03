## Under the hood

Clinician's Veil is a Tauri app for Apple Silicon Macs. The interface is TypeScript in a
WKWebView, and it can reach the rest of the Mac only through a small set of Tauri commands. The
domain logic is a plain Rust library that knows nothing about Tauri, the speech engine or the web
framework. Each outside concern sits behind its own adapter.

![The web interface calls narrow Tauri commands, which call the Rust core. The core uses an encrypted SQLCipher library, identifier detection with rules and BERT on ONNX, and whisper.cpp dictation on Metal, all on the Mac. Its egress adapter is the only route out, sending one approved request to OpenAI's Responses API with storage off and redirects refused.]({{media:architecture.svg}})

- **Shell:** Tauri 2 with WKWebView, Vite and TypeScript, ProseMirror for the letter editor
- **Core:** Rust, with no Python sidecar and no local server
- **Detection:** Rust pattern rules plus a pinned, quantised BERT model run on ONNX
- **Dictation:** Whisper large-v3 turbo through statically linked whisper.cpp on Metal, gated by Silero voice detection
- **Storage:** SQLCipher with FTS5 keyword search, the key kept in the macOS Keychain
- **Generation:** OpenAI's Responses API, GPT-6 Luna, Sol or Astra and earlier models, chosen per document
- **Distribution:** an arm64 DMG built and signature-checked in GitHub Actions on every merge

## A token is only good for one request

Preparing a request replaces every reviewed identifier with a token that names this preparation
and a sequence number. Each token is bound to exactly one original, so two people who share a
label still come back as themselves. Details the clinician removed are simply absent.

```rust
// crates/clinicians-veil-core/src/document_generation.rs
for item in material.items.into_iter().rev() {
    if !eligible_placeholder(&item.replacement) {
        continue;
    }
    let token = format!("⟪CV_{}_{:04}⟫", preparation_id, restorations.len() + 1);
    text.replace_range(item.output_start..item.output_end, &token);
    restorations.push(RestorationRecord {
        token,
        original: item.original,
        source_note_id: note.id,
        source_item_id: item.item_id,
    });
}
```

Restoring the reply is one bounded pass. A token is swapped back only if it matches one this
request issued exactly. Anything else, including a token the model has altered, is left visible
in the draft for the clinician to resolve. Nothing is guessed, and the saved redaction library is
never consulted.

## One request, sent carefully

The egress adapter refuses redirects, checks the destination against the one allowed origin, and
asks OpenAI not to store the request. It sends no tools and no history, and it never retries on
its own. A failed send discards the prepared request, so a retry is a fresh approval.

```rust
// src-tauri/src/privacy.rs, condensed
let client = reqwest::blocking::Client::builder()
    .redirect(reqwest::redirect::Policy::none())
    .timeout(std::time::Duration::from_secs(90))
    .build()?;
let payload = serde_json::json!({
    "model": prepared.model,
    "instructions": prepared.instructions,
    "input": prepared.input,
    "store": false,
    "background": false,
    "tools": [],
    "tool_choice": "none",
});
client.post("https://api.openai.com/v1/responses")
    .bearer_auth(key)
    .json(&payload)
    .send()
```

The prepared request is bound to a digest of its instructions, notes, model, destination and
purpose, so any change makes a new request that has to be approved again. The record kept of each
send holds no content: the time, the destination, the digest, the outcome and the cost.

## What it does not claim

It is a personal tool for one clinician on one Mac, not a clinical product. De-identification
reduces risk; it does not make a note anonymous, and it is not a compliance certificate. The
local models miss initials, partial organisation names and identifying combinations, which is why
the review step is not optional. A local Llama pass for context-based detection is planned and is
not in this version.
