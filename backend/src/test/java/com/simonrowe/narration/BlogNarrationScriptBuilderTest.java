package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlogNarrationScriptBuilderTest {

  private final BlogNarrationScriptBuilder builder = new BlogNarrationScriptBuilder();

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
}
