package com.simonrowe.coparent.assistant;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.FamilyDocumentRepository;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

/**
 * Quick add's read-only {@code find_documents} lookup (spec 057 §2.4): lets the model see which
 * documents the family already keeps, so a renewed passport becomes a new version of the old one
 * rather than a second "Passport".
 *
 * <p>Details only: id, title, children, section and dates. Never a note, never a file, never an
 * archived document. Scoped to the family the batch belongs to, whose membership the caller has
 * already checked.
 */
@Component
public class AssistantDocumentLookup {

  static final int MAX_RESULTS = 20;
  private static final int MIN_WORD = 2;

  private final FamilyDocumentRepository documents;
  private final ChildRepository children;

  public AssistantDocumentLookup(final FamilyDocumentRepository documents,
      final ChildRepository children) {
    this.documents = documents;
    this.children = children;
  }

  /**
   * Finds the family's unarchived documents matching the model's arguments.
   *
   * @param familyId the batch's family
   * @param arguments {@code query} (words to look for in titles and children's names),
   *     {@code childId} and {@code section}, each optional
   * @return at most {@value #MAX_RESULTS} documents, best match first
   */
  public List<Map<String, Object>> find(final ObjectId familyId,
      final Map<String, Object> arguments) {
    final Map<String, Object> args = arguments == null ? Map.of() : arguments;
    final Map<ObjectId, String> names = new HashMap<>();
    for (final Child child : children.findByFamilyIdAndDeletedAtIsNull(familyId)) {
      names.put(child.id(), child.fullName() == null ? "" : child.fullName());
    }
    final ObjectId childId = args.get("childId") instanceof String id && ObjectId.isValid(id)
        ? new ObjectId(id) : null;
    final DocumentSection section = section(args.get("section"));
    final List<String> words = words(args.get("query") instanceof String query ? query : "");
    return documents.findByFamilyIdAndArchivedAtIsNullOrderByDatedOnDescCreatedAtDesc(familyId)
        .stream()
        .filter(document -> childId == null || document.childIds().isEmpty()
            || document.childIds().contains(childId))
        .filter(document -> section == null || document.section() == section)
        .map(document -> Map.entry(document, score(document, words, names)))
        .filter(entry -> words.isEmpty() || entry.getValue() > 0)
        .sorted(Comparator.comparingInt((Map.Entry<FamilyDocument, Integer> e) -> e.getValue())
            .reversed())
        .limit(MAX_RESULTS)
        .map(entry -> describe(entry.getKey()))
        .toList();
  }

  private static int score(final FamilyDocument document, final List<String> words,
      final Map<ObjectId, String> names) {
    final StringBuilder text = new StringBuilder(document.title() == null ? ""
        : document.title()).append(' ').append(document.section().name());
    document.childIds().forEach(id -> text.append(' ').append(names.getOrDefault(id, "")));
    final List<String> haystack = words(text.toString());
    return (int) words.stream().filter(word -> haystack.stream()
        .anyMatch(candidate -> candidate.startsWith(word))).count();
  }

  private static Map<String, Object> describe(final FamilyDocument document) {
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", document.id().toHexString());
    result.put("title", document.title());
    result.put("childIds", document.childIds().stream().map(ObjectId::toHexString).toList());
    result.put("section", document.section().name());
    result.put("datedOn", Objects.toString(document.datedOn(), null));
    result.put("dueOn", Objects.toString(document.dueOn(), null));
    result.put("dueKind", document.dueKind() == null ? null : document.dueKind().name());
    result.put("versions", document.versions().size());
    return result;
  }

  private static DocumentSection section(final Object value) {
    if (!(value instanceof String text) || text.isBlank()) {
      return null;
    }
    try {
      return DocumentSection.valueOf(text.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  /** Lower-cased words, possessives dropped, split on anything that is not a letter or digit. */
  private static List<String> words(final String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).replace("'s", "").replace("’s", "")
            .split("[^\\p{L}\\p{N}]++"))
        .filter(word -> word.length() >= MIN_WORD)
        .toList();
  }
}
