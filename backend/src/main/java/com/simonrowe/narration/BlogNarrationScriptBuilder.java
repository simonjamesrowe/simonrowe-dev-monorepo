package com.simonrowe.narration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;

@Component
public class BlogNarrationScriptBuilder {

  static final String FORMAT_VERSION = "blog-narration-v1";
  private static final Pattern FENCED_CODE = Pattern.compile(
      "(?s)(```.*?```|~~~.*?~~~)");
  private static final Pattern INDENTED_CODE = Pattern.compile(
      "(?m)^(?: {4}|\\t)\\S.*$");
  private static final Pattern IMAGE = Pattern.compile(
      "!\\[[^]]*]\\([^)]*\\)");
  private static final Pattern LINK = Pattern.compile(
      "\\[([^]]+)]\\(https?://[^)]*\\)");
  private static final Pattern RAW_HTML = Pattern.compile("<[^>]+>");
  private static final Pattern URL = Pattern.compile("https?://\\S+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern TABLE_SEPARATOR = Pattern.compile(
      "^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$");

  private final Parser parser = Parser.builder().build();
  private final TextContentRenderer renderer = TextContentRenderer.builder().build();

  public String build(final String title, final String markdown) {
    String source = markdown == null ? "" : markdown;
    source = FENCED_CODE.matcher(source).replaceAll(" Code example omitted. ");
    source = INDENTED_CODE.matcher(source).replaceAll(" Code example omitted. ");
    source = removeTables(source);
    source = IMAGE.matcher(source).replaceAll(" ");
    source = LINK.matcher(source).replaceAll("$1");
    source = RAW_HTML.matcher(source).replaceAll(" ");
    String prose = renderer.render(parser.parse(source));
    prose = URL.matcher(prose).replaceAll(" ");
    String safeTitle = title == null ? "" : title.trim();
    return WHITESPACE.matcher(safeTitle + ". " + prose).replaceAll(" ").trim();
  }

  public String fingerprint(
      final String script,
      final String voiceName,
      final String languageCode,
      final String encoding
  ) {
    String source = String.join("\n", FORMAT_VERSION, script, voiceName,
        languageCode, encoding);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(
          source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }

  private String removeTables(final String source) {
    StringBuilder result = new StringBuilder();
    boolean omitted = false;
    for (String line : source.split("\\R", -1)) {
      boolean tableLine = TABLE_SEPARATOR.matcher(line).matches()
          || line.trim().startsWith("|") && line.trim().endsWith("|");
      if (tableLine) {
        if (!omitted) {
          result.append(" Table omitted. ");
          omitted = true;
        }
      } else {
        result.append(line).append('\n');
        omitted = false;
      }
    }
    return result.toString();
  }
}
