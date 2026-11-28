package com.simonrowe.coparent.document;

import com.simonrowe.coparent.model.DocumentNotice;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;

/** Words for the batched document email: "Alex added 6 documents and archived 1". */
public final class DocumentNoticeText {

  private DocumentNoticeText() {
  }

  /** One change, with the document's current title. */
  public record Line(DocumentNotice.Change change, String title, ObjectId documentId) {
  }

  /** The subject line, naming {@code who} and what they did. */
  public static String summary(final String who, final List<Line> lines) {
    if (lines.size() == 1) {
      final Line line = lines.getFirst();
      return "%s %s %s".formatted(who, verb(line.change(), true), line.title());
    }
    final Map<DocumentNotice.Change, Integer> counts = new EnumMap<>(DocumentNotice.Change.class);
    lines.forEach(line -> counts.merge(line.change(), 1, Integer::sum));
    final List<String> phrases = new ArrayList<>();
    for (final Map.Entry<DocumentNotice.Change, Integer> entry : counts.entrySet()) {
      final int count = entry.getValue();
      final String noun = phrases.isEmpty() ? (count == 1 ? " document" : " documents") : "";
      phrases.add("%s %d%s".formatted(verb(entry.getKey(), count == 1), count, noun));
    }
    return who + " " + join(phrases);
  }

  /** The verb for a change, ahead of a title or a count. */
  static String verb(final DocumentNotice.Change change, final boolean single) {
    return switch (change) {
      case ADDED -> "added";
      case NEW_VERSION -> single ? "uploaded a new version of" : "uploaded new versions of";
      case ARCHIVED -> "archived";
      case RESTORED -> "restored";
    };
  }

  private static String join(final List<String> phrases) {
    if (phrases.size() == 1) {
      return phrases.getFirst();
    }
    final String head = String.join(", ", phrases.subList(0, phrases.size() - 1));
    return "%s and %s".formatted(head, phrases.getLast());
  }
}
