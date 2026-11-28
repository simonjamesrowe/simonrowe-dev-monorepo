package com.simonrowe.coparent.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.coparent.model.DocumentNotice.Change;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class DocumentNoticeTextTest {

  @Test
  void oneChangeNamesTheDocument() {
    assertThat(DocumentNoticeText.summary("Alex", List.of(line(Change.ADDED, "Passport"))))
        .isEqualTo("Alex added Passport");
    assertThat(DocumentNoticeText.summary("Alex", List.of(line(Change.NEW_VERSION, "Passport"))))
        .isEqualTo("Alex uploaded a new version of Passport");
  }

  @Test
  void severalChangesAreCountedWithTheNounOnce() {
    final List<DocumentNoticeText.Line> lines = new ArrayList<>(
        Collections.nCopies(6, line(Change.ADDED, "Letter")));
    lines.add(line(Change.ARCHIVED, "Old letter"));

    assertThat(DocumentNoticeText.summary("Alex", lines))
        .isEqualTo("Alex added 6 documents and archived 1");
  }

  @Test
  void threeKindsAreListed() {
    assertThat(DocumentNoticeText.summary("Sam", List.of(line(Change.ADDED, "A"),
        line(Change.NEW_VERSION, "B"), line(Change.NEW_VERSION, "C"),
        line(Change.RESTORED, "D"))))
        .isEqualTo("Sam added 1 document, uploaded new versions of 2 and restored 1");
  }

  private static DocumentNoticeText.Line line(final Change change, final String title) {
    return new DocumentNoticeText.Line(change, title, new ObjectId());
  }
}
