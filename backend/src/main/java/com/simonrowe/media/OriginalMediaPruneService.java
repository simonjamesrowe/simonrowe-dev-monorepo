package com.simonrowe.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Reclaims disk (and therefore backup) space by removing the full-size
 * {@code original.*} media files that the public site never serves.
 *
 * <p>The media pipeline stores an original plus thumbnail/small/medium/large
 * variants per asset. Structured image fields (blog featured image, profile,
 * company and skill images) are always swapped to a variant at serve time by
 * {@link MediaVariantResolver}/{@link MediaImageHydrator}, so their originals are
 * dead weight. Markdown bodies are the exception: the admin editors embed the
 * raw {@code originalPath} inline (e.g. {@code ![](/uploads/<id>/original.png)}),
 * and those bodies are rendered as-is.
 *
 * <p>This service therefore runs in two phases:
 * <ol>
 *   <li>rewrite every inline {@code original.*} reference in the markdown body
 *       collections to the asset's best available variant;</li>
 *   <li>delete the {@code original.*} files for every asset that has a variant
 *       set (SVGs and any variant-less asset keep their original, since the
 *       resolver falls back to serving it).</li>
 * </ol>
 *
 * <p>Media asset documents are left untouched — {@code originalPath} still keys
 * the resolver lookups. Originals also live on Google Drive via the media
 * backup, so nothing is irrecoverable. Idempotent: a second run finds no inline
 * references and no {@code original.*} files.
 */
@Service
public class OriginalMediaPruneService {

  private static final Logger LOG =
      LoggerFactory.getLogger(OriginalMediaPruneService.class);

  /** Matches an inline reference to a managed original file, e.g.
   * {@code /uploads/2f1c.../original.png}, stopping before markdown/HTML
   * delimiters. */
  private static final Pattern ORIGINAL_REF =
      Pattern.compile("/uploads/[^\\s\"')]+/original\\.[A-Za-z0-9]+");

  private static final String ORIGINAL_PREFIX = "original.";
  private static final String UPLOADS_URL_PREFIX = "/uploads/";

  /** Collection/field pairs holding rendered markdown bodies. */
  private static final List<String[]> BODY_FIELDS = List.of(
      new String[]{"blogs", "content"},
      new String[]{"skill_groups", "description"},
      new String[]{"tourSteps", "description"}
  );

  /** Preferred variant order when replacing an inline original reference. */
  private static final List<String> VARIANT_PREFERENCE =
      List.of("large", "medium", "small", "thumbnail");

  private final MongoTemplate mongoTemplate;
  private final MediaAssetRepository mediaAssetRepository;
  private final String uploadsPath;

  public OriginalMediaPruneService(
      final MongoTemplate mongoTemplate,
      final MediaAssetRepository mediaAssetRepository,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath) {
    this.mongoTemplate = mongoTemplate;
    this.mediaAssetRepository = mediaAssetRepository;
    this.uploadsPath = uploadsPath;
  }

  /** Runs both phases and logs a summary. */
  public void prune() {
    Map<String, String> originalToVariant = buildOriginalToVariantMap();
    int rewritten = rewriteInlineReferences(originalToVariant);
    long[] deleted = deleteOriginalFiles();

    LOG.info(
        "Original media prune complete: rewrote {} inline reference(s), "
            + "deleted {} original file(s), reclaimed {} MB",
        rewritten, deleted[0], deleted[1] / (1024 * 1024));
  }

  /** Maps each asset's {@code originalPath} to its best variant path, skipping
   * assets that have no variants (SVGs and variant-less assets). */
  private Map<String, String> buildOriginalToVariantMap() {
    Map<String, String> map = new HashMap<>();
    for (MediaAsset asset : mediaAssetRepository.findAll()) {
      if (asset.originalPath() == null || asset.originalPath().isBlank()) {
        continue;
      }
      String variantPath = bestVariantPath(asset);
      if (variantPath != null) {
        map.put(asset.originalPath(), variantPath);
      }
    }
    return map;
  }

  /** Returns the highest-preference variant path for an asset, or {@code null}
   * when it has no usable variant. */
  static String bestVariantPath(final MediaAsset asset) {
    Map<String, MediaAsset.VariantInfo> variants = asset.variants();
    if (variants == null || variants.isEmpty()) {
      return null;
    }
    for (String name : VARIANT_PREFERENCE) {
      MediaAsset.VariantInfo variant = variants.get(name);
      if (variant != null && variant.path() != null && !variant.path().isBlank()) {
        return variant.path();
      }
    }
    return null;
  }

  private int rewriteInlineReferences(final Map<String, String> originalToVariant) {
    if (originalToVariant.isEmpty()) {
      return 0;
    }
    int rewritten = 0;
    for (String[] target : BODY_FIELDS) {
      String collection = target[0];
      String field = target[1];
      Query query = new Query(Criteria.where(field).regex(ORIGINAL_REF));
      for (Document doc : mongoTemplate.find(query, Document.class, collection)) {
        if (!(doc.get(field) instanceof String body)) {
          continue;
        }
        String updated = rewriteReferences(body, originalToVariant);
        if (!updated.equals(body)) {
          mongoTemplate.updateFirst(
              new Query(Criteria.where("_id").is(doc.get("_id"))),
              new Update().set(field, updated),
              collection);
          rewritten++;
        }
      }
    }
    return rewritten;
  }

  /** Replaces each inline {@code original.*} reference that has a known variant;
   * unknown references (e.g. SVG originals) are left untouched. */
  static String rewriteReferences(
      final String body, final Map<String, String> originalToVariant) {
    if (body == null || body.isEmpty() || originalToVariant.isEmpty()) {
      return body;
    }
    Matcher matcher = ORIGINAL_REF.matcher(body);
    StringBuilder result = new StringBuilder();
    boolean changed = false;
    while (matcher.find()) {
      String reference = matcher.group();
      String variant = originalToVariant.get(reference);
      String replacement = variant != null ? variant : reference;
      changed = changed || variant != null;
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return changed ? result.toString() : body;
  }

  /** Deletes {@code original.*} files for assets that have variants. Returns
   * {@code [fileCount, bytesReclaimed]}. */
  private long[] deleteOriginalFiles() {
    long count = 0;
    long bytes = 0;
    for (MediaAsset asset : mediaAssetRepository.findAll()) {
      if (bestVariantPath(asset) == null) {
        continue;
      }
      Path file = resolveDiskPath(asset.originalPath());
      if (file == null || !file.getFileName().toString().startsWith(ORIGINAL_PREFIX)) {
        continue;
      }
      try {
        if (Files.isRegularFile(file)) {
          long size = Files.size(file);
          Files.delete(file);
          count++;
          bytes += size;
        }
      } catch (IOException e) {
        LOG.warn("Failed to delete original media file: {}", file, e);
      }
    }
    return new long[]{count, bytes};
  }

  /** Maps a stored {@code /uploads/...} path to its on-disk location under the
   * configured uploads directory. */
  Path resolveDiskPath(final String originalPath) {
    if (originalPath == null || originalPath.isBlank()) {
      return null;
    }
    String relative = originalPath.startsWith(UPLOADS_URL_PREFIX)
        ? originalPath.substring(UPLOADS_URL_PREFIX.length())
        : originalPath.startsWith("/") ? originalPath.substring(1) : originalPath;
    return Path.of(uploadsPath, relative);
  }
}
