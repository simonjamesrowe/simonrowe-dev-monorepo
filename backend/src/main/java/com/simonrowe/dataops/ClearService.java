package com.simonrowe.dataops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClearService {

  private static final Logger LOG = LoggerFactory.getLogger(ClearService.class);
  private static final Set<String> COLLECTIONS = Set.of(
      "blogs", "tags", "skills", "skill_groups", "jobs",
      "profiles", "social_medias", "tourSteps", "media_assets"
  );

  private final MongoTemplate mongoTemplate;
  private final DataOperationsService operationsService;
  private final com.simonrowe.search.IndexService indexService;
  private final String uploadsPath;

  public ClearService(
      final MongoTemplate mongoTemplate,
      final DataOperationsService operationsService,
      final com.simonrowe.search.IndexService indexService,
      @Value("${uploads.path:backend/uploads/}") final String uploadsPath
  ) {
    this.mongoTemplate = mongoTemplate;
    this.operationsService = operationsService;
    this.indexService = indexService;
    this.uploadsPath = uploadsPath;
  }

  public void performClear() {
    try {
      operationsService.updateProgress("Clearing database collections...", 10);
      int progress = 10;
      int progressPerCollection = 50 / COLLECTIONS.size();

      for (String collectionName : COLLECTIONS) {
        operationsService.updateProgress(
            "Clearing collection: " + collectionName, progress);
        mongoTemplate.dropCollection(collectionName);
        progress += progressPerCollection;
      }

      operationsService.updateProgress("Deleting uploaded media files...", 65);
      deleteUploads();

      operationsService.updateProgress("Clearing search indices...", 80);
      indexService.fullSyncSiteIndex();
      indexService.fullSyncBlogIndex();

      operationsService.completeOperation(
          "All local data cleared. " + COLLECTIONS.size()
              + " collections dropped, media files deleted, search indices cleared.");

    } catch (Exception ex) {
      LOG.error("Clear operation failed", ex);
      operationsService.failOperation("Clear failed: " + ex.getMessage());
    }
  }

  private void deleteUploads() {
    Path uploadsDir = Path.of(uploadsPath);
    if (!Files.exists(uploadsDir)) {
      return;
    }
    try {
      Files.walk(uploadsDir)
          .sorted(Comparator.reverseOrder())
          .filter(p -> !p.equals(uploadsDir))
          .forEach(p -> {
            try {
              Files.delete(p);
            } catch (IOException ex) {
              LOG.warn("Failed to delete: {}", p, ex);
            }
          });
    } catch (IOException ex) {
      LOG.warn("Failed to walk uploads directory for cleanup", ex);
    }
  }
}
