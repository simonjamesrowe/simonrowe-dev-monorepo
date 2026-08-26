package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NarrationScriptBuilderTest {

  private final NarrationScriptBuilder builder = new NarrationScriptBuilder();

  @Test
  void convertsMarkdownToSafeDeterministicSpeech() {
    String markdown = """
        # Introduction

        Read [the guide](https://example.com/guide) and enjoy it.

        ![diagram](/image.png)

        ```java
        System.out.println("secret");
        ```

        | Name | Value |
        | --- | --- |
        | one | two |

        <aside>Closing thought</aside>
        """;

    String script = builder.build("A useful post", markdown);

    assertThat(script)
        .contains("A useful post", "Introduction", "Read the guide and enjoy it")
        .contains("Code example omitted", "Table omitted", "Closing thought")
        .doesNotContain("example.com", "System.out", "diagram", "<aside>");
  }

  @Test
  void fingerprintChangesWithSpeechOrVoiceButNotAcrossRepeatedCalls() {
    String first = builder.fingerprint("Hello", "voice-a", "en-GB", "MP3");

    assertThat(builder.fingerprint("Hello", "voice-a", "en-GB", "MP3"))
        .isEqualTo(first)
        .hasSize(64);
    assertThat(builder.fingerprint("Hello!", "voice-a", "en-GB", "MP3"))
        .isNotEqualTo(first);
    assertThat(builder.fingerprint("Hello", "voice-b", "en-GB", "MP3"))
        .isNotEqualTo(first);
  }

  /**
   * The format version feeds the fingerprint, which is the narration {@code _id} and the
   * directory its MP3 lives in. It stays the literal {@code blog-narration-v1} even though
   * the class is no longer blog-specific: renaming it would change every existing blog
   * narration's id and orphan every stored audio file.
   */
  @Test
  void formatVersionStaysPinnedSoExistingAudioIsNotOrphaned() {
    assertThat(NarrationScriptBuilder.FORMAT_VERSION).isEqualTo("blog-narration-v1");
  }

  @Test
  void fingerprintIsStableForTheSameScriptAndVoiceSettings() {
    NarrationScriptBuilder builder = new NarrationScriptBuilder();

    assertThat(builder.fingerprint("Script.", "voice", "en-GB", "MP3"))
        .isEqualTo(builder.fingerprint("Script.", "voice", "en-GB", "MP3"));
  }

  @Test
  void fingerprintChangesWhenTheScriptChangesSoOldAudioGoesStale() {
    NarrationScriptBuilder builder = new NarrationScriptBuilder();

    assertThat(builder.fingerprint("Script one.", "voice", "en-GB", "MP3"))
        .isNotEqualTo(builder.fingerprint("Script two.", "voice", "en-GB", "MP3"));
  }
}
