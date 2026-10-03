## One note, from the clinic to the letter

Everything up to the request happens on the Mac, and so does everything after it. One approved
request is the only thing that leaves.

![What stays on the Mac and what leaves. A clinical note is captured, its identifiers are found by local rules and a BERT model, the clinician reviews every change and the note is saved to an encrypted library. Only a request with one-off tokens in place of each detail goes to OpenAI, after an explicit approval. The draft comes back, the details are put back on the Mac, and the letter is reviewed and exported.]({{media:stays-and-leaves.svg}})

| Step | Where | What happens |
| --- | --- | --- |
| Capture | On the Mac | Type or paste a note, import a Word document, PDF or text file, or dictate. Dictation is transcribed by Whisper on the Mac, and the audio is never written to disk. |
| Find identifiers | On the Mac | Pattern rules find dates, references, emails, phone numbers and postcodes. A BERT model running on ONNX finds names, places and organisations. Nothing is sent anywhere to do it. |
| Review | On the Mac | Each proposal is accepted, relabelled, kept or removed. Anything the models missed is selected in the text and added by hand. A final local check runs before the note can be saved. |
| Save | On the Mac | The original, the reviewed text and every decision are saved together in an encrypted SQLCipher library, searchable on the Mac. |
| Prepare | On the Mac | Choosing a template and notes builds the exact request. Every identifier becomes a one-off token, and the clinician sees the destination, the model and an estimated cost. |
| Send | Leaves the Mac, once | One request to OpenAI's Responses API, after an explicit approval of that request. Provider-side storage is off, and there are no tools and no conversation history. |
| Restore | On the Mac | The tokens in the reply are swapped back for the original details, in one pass. The letter is a draft for review, then saved or exported as a PDF. |

## The models miss things, so the clinician decides

The local models are a first pass, not a guarantee. On the synthetic note in the demo they found
the patient's name, the dates, the email, the phone number and the postcode. They also flagged the
word "SYNTHETIC" from the test-data banner as an organisation, and missed the case reference and
the house number. So every proposal is a decision for the clinician, and selecting any missed
phrase adds it to the review. No detections is never treated as proof that a note is
de-identified.

## What never goes in the request

- **The patient's identifying details.** Each accepted or added identifier is replaced by a token
  such as `⟪CV_2bc4…_0016⟫` that means nothing outside this one request.
- **Note titles, patient records and the redaction mappings.** The request is built from the
  reviewed text alone.
- **The clinician's details and signature.** They are added to the letter locally, when it is
  exported.
- **The original text, the audio and the detection history.** They stay in the library.

The request itself carries the clinical narrative, pseudonymised. That is what a model needs to
write the letter, and it is why every request is shown in full before it is approved.

## Sending is switched off until it is set up

Clinical sending stays off until four confirmations are recorded in Settings: organisational
approval for the use case, a review of the model and provider terms, the retention and
regional-processing arrangements, and a rollback plan. Changing the API key or the default model
switches it off again. An approval covers one request and is never remembered: editing anything
makes a new request that needs approving again. With sending off, the app cannot build a clinical
request at all, even with a key saved.

The demo uses synthetic data only. Using real patient information needs the employing
organisation's information-governance approval first, and this tool does not provide that.
