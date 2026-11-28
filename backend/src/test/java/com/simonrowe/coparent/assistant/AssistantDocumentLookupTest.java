package com.simonrowe.coparent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.DocumentSource;
import com.simonrowe.coparent.model.DocumentVersion;
import com.simonrowe.coparent.model.DueKind;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.FamilyDocumentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class AssistantDocumentLookupTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final ObjectId familyId = new ObjectId();
  private final ObjectId mia = new ObjectId();
  private final ObjectId leo = new ObjectId();
  private final FamilyDocumentRepository documents = mock(FamilyDocumentRepository.class);
  private final ChildRepository children = mock(ChildRepository.class);
  private final AssistantDocumentLookup lookup = new AssistantDocumentLookup(documents, children);
  private final FamilyDocument miaPassport = document("Passport", DocumentSection.IDENTITY, mia);
  private final FamilyDocument leoPassport = document("Passport", DocumentSection.IDENTITY, leo);
  private final FamilyDocument order = document("Child arrangements order",
      DocumentSection.AGREEMENTS, null);

  AssistantDocumentLookupTest() {
    when(children.findByFamilyIdAndDeletedAtIsNull(familyId)).thenReturn(List.of(
        new Child(mia, familyId, "Mia Taylor", LocalDate.of(2018, 3, 1), null, null, null, null,
            NOW, NOW),
        new Child(leo, familyId, "Leo Taylor", LocalDate.of(2021, 1, 1), null, null, null, null,
            NOW, NOW)));
    when(documents.findByFamilyIdAndArchivedAtIsNullOrderByDatedOnDescCreatedAtDesc(familyId))
        .thenReturn(List.of(order, miaPassport, leoPassport));
  }

  @Test
  void ranksTheChildsOwnDocumentFirstAndGivesDetailsOnly() {
    final List<Map<String, Object>> found = lookup.find(familyId,
        Map.of("query", "Leo's passport"));

    assertThat(found).extracting(result -> result.get("id"))
        .containsExactly(leoPassport.id().toHexString(), miaPassport.id().toHexString());
    assertThat(found.getFirst()).containsOnlyKeys("id", "title", "childIds", "section",
        "datedOn", "dueOn", "dueKind", "versions")
        .containsEntry("dueKind", "EXPIRES").containsEntry("section", "IDENTITY");
  }

  @Test
  void filtersByChildAndSectionAndListsEverythingWithNoQuery() {
    final Map<String, Object> args = new HashMap<>();
    args.put("query", null);
    args.put("childId", mia.toHexString());
    args.put("section", null);
    assertThat(lookup.find(familyId, args)).extracting(result -> result.get("title"))
        .as("a whole-family document belongs to every child")
        .containsExactly("Child arrangements order", "Passport");
    assertThat(lookup.find(familyId, Map.of("section", "agreements")))
        .extracting(result -> result.get("title")).containsExactly("Child arrangements order");
    assertThat(lookup.find(familyId, Map.of("section", "nonsense"))).hasSize(3);
    assertThat(lookup.find(familyId, null)).hasSize(3);
    assertThat(lookup.find(familyId, Map.of("query", "birth certificate"))).isEmpty();
  }

  private FamilyDocument document(final String title, final DocumentSection section,
      final ObjectId child) {
    final ObjectId parent = new ObjectId();
    final LocalDate dated = LocalDate.of(2025, 8, 2);
    return new FamilyDocument(new ObjectId(), familyId, child == null ? List.of() : List.of(child),
        section, title, dated, LocalDate.of(2035, 8, 2), DueKind.EXPIRES, "a private note",
        List.of(new DocumentVersion(new ObjectId(), List.of(), dated, LocalDate.of(2035, 8, 2),
            DueKind.EXPIRES, parent, NOW)),
        DocumentSource.upload(), null, null, Set.of(), parent, NOW, NOW, 1);
  }
}
