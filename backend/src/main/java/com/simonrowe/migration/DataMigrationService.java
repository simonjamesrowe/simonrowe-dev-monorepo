package com.simonrowe.migration;

import com.simonrowe.admin.AdminBlogRepository;
import com.simonrowe.admin.AdminJobRepository;
import com.simonrowe.admin.AdminProfileRepository;
import com.simonrowe.admin.AdminSkillGroupRepository;
import com.simonrowe.admin.AdminSkillRepository;
import com.simonrowe.admin.AdminSocialMediaRepository;
import com.simonrowe.admin.AdminTagRepository;
import com.simonrowe.admin.AdminTourStepRepository;
import com.simonrowe.admin.Blog;
import com.simonrowe.admin.Job;
import com.simonrowe.admin.Profile;
import com.simonrowe.admin.Skill;
import com.simonrowe.admin.SkillGroup;
import com.simonrowe.admin.SocialMediaLink;
import com.simonrowe.admin.Tag;
import com.simonrowe.admin.TourStep;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.BsonBinaryReader;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Profile("migrate")
public class DataMigrationService implements ApplicationRunner {

  private static final Logger LOG =
      LoggerFactory.getLogger(DataMigrationService.class);

  private final AdminTagRepository tagRepository;
  private final AdminSkillRepository skillRepository;
  private final AdminSkillGroupRepository skillGroupRepository;
  private final AdminProfileRepository profileRepository;
  private final AdminSocialMediaRepository socialMediaRepository;
  private final AdminJobRepository jobRepository;
  private final AdminBlogRepository blogRepository;
  private final AdminTourStepRepository tourStepRepository;

  @Value("${migration.backup-path}")
  private String backupPath;

  public DataMigrationService(
      final AdminTagRepository tagRepository,
      final AdminSkillRepository skillRepository,
      final AdminSkillGroupRepository skillGroupRepository,
      final AdminProfileRepository profileRepository,
      final AdminSocialMediaRepository socialMediaRepository,
      final AdminJobRepository jobRepository,
      final AdminBlogRepository blogRepository,
      final AdminTourStepRepository tourStepRepository
  ) {
    this.tagRepository = tagRepository;
    this.skillRepository = skillRepository;
    this.skillGroupRepository = skillGroupRepository;
    this.profileRepository = profileRepository;
    this.socialMediaRepository = socialMediaRepository;
    this.jobRepository = jobRepository;
    this.blogRepository = blogRepository;
    this.tourStepRepository = tourStepRepository;
  }

  @Override
  public void run(final ApplicationArguments args) {
    LOG.info("Starting data migration from backup path: {}", backupPath);

    final Map<String, String> tagIdMap = migrateTags();
    final Map<String, String> skillIdMap = migrateSkills();
    migrateSkillGroups(skillIdMap);
    migrateProfiles();
    migrateSocialMediaLinks();
    migrateJobs(skillIdMap);
    migrateBlogs(tagIdMap, skillIdMap);
    migrateTourSteps();

    LOG.info("Data migration complete.");
  }

  private Map<String, String> migrateTags() {
    LOG.info("Migrating tags...");
    Map<String, String> idMap = new HashMap<>();
    List<Document> docs = readBsonFile("tags.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      Optional<Tag> existing = tagRepository.findByLegacyId(legacyId);
      String newId;

      if (existing.isPresent()) {
        newId = existing.get().id();
        Tag updated = new Tag(
            newId,
            getString(doc, "name"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        tagRepository.save(updated);
      } else {
        Tag tag = new Tag(
            null,
            getString(doc, "name"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        Tag savedTag = tagRepository.save(tag);
        newId = savedTag.id();
      }

      idMap.put(legacyId, newId);
      saved++;
    }

    LOG.info("Tags migration complete: {} upserted, {} skipped.", saved, skipped);
    return idMap;
  }

  private Map<String, String> migrateSkills() {
    LOG.info("Migrating skills...");
    Map<String, String> idMap = new HashMap<>();
    List<Document> docs = readBsonFile("skills.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      Optional<Skill> existing = skillRepository.findByLegacyId(legacyId);
      String newId;

      if (existing.isPresent()) {
        newId = existing.get().id();
        Skill updated = new Skill(
            newId,
            getString(doc, "name"),
            getDouble(doc, "rating"),
            getString(doc, "description"),
            getString(doc, "image"),
            getInt(doc, "order"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        skillRepository.save(updated);
      } else {
        Skill skill = new Skill(
            null,
            getString(doc, "name"),
            getDouble(doc, "rating"),
            getString(doc, "description"),
            getString(doc, "image"),
            getInt(doc, "order"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        Skill savedSkill = skillRepository.save(skill);
        newId = savedSkill.id();
      }

      idMap.put(legacyId, newId);
      saved++;
    }

    LOG.info("Skills migration complete: {} upserted, {} skipped.", saved, skipped);
    return idMap;
  }

  private void migrateSkillGroups(final Map<String, String> skillIdMap) {
    LOG.info("Migrating skill groups...");
    List<Document> docs = readBsonFile("skills_groups.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      List<String> skillIds = mapIds(getLegacyRefIds(doc, "skills"), skillIdMap);
      Optional<SkillGroup> existing = skillGroupRepository.findByLegacyId(legacyId);

      if (existing.isPresent()) {
        SkillGroup updated = new SkillGroup(
            existing.get().id(),
            getString(doc, "name"),
            getDouble(doc, "rating"),
            getString(doc, "description"),
            getString(doc, "image"),
            getInt(doc, "order"),
            skillIds,
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        skillGroupRepository.save(updated);
      } else {
        SkillGroup group = new SkillGroup(
            null,
            getString(doc, "name"),
            getDouble(doc, "rating"),
            getString(doc, "description"),
            getString(doc, "image"),
            getInt(doc, "order"),
            skillIds,
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        skillGroupRepository.save(group);
      }

      saved++;
    }

    LOG.info("Skill groups migration complete: {} upserted, {} skipped.", saved, skipped);
  }

  private void migrateProfiles() {
    LOG.info("Migrating profiles...");
    List<Document> docs = readBsonFile("profiles.bson");
    int saved = 0;

    for (Document doc : docs) {
      List<Profile> existing = profileRepository.findAll();
      Instant createdAt = toInstant(doc.get("createdAt"));
      Instant updatedAt = toInstant(doc.get("updatedAt"));

      if (!existing.isEmpty()) {
        Profile updated = new Profile(
            existing.getFirst().id(),
            getString(doc, "name"),
            getString(doc, "title"),
            getString(doc, "headline"),
            getString(doc, "description"),
            getString(doc, "location"),
            getString(doc, "phoneNumber"),
            getString(doc, "primaryEmail"),
            getString(doc, "secondaryEmail"),
            getString(doc, "profileImage"),
            getString(doc, "sidebarImage"),
            getString(doc, "backgroundImage"),
            getString(doc, "mobileBackgroundImage"),
            createdAt,
            updatedAt
        );
        profileRepository.save(updated);
      } else {
        Profile profile = new Profile(
            null,
            getString(doc, "name"),
            getString(doc, "title"),
            getString(doc, "headline"),
            getString(doc, "description"),
            getString(doc, "location"),
            getString(doc, "phoneNumber"),
            getString(doc, "primaryEmail"),
            getString(doc, "secondaryEmail"),
            getString(doc, "profileImage"),
            getString(doc, "sidebarImage"),
            getString(doc, "backgroundImage"),
            getString(doc, "mobileBackgroundImage"),
            createdAt,
            updatedAt
        );
        profileRepository.save(profile);
      }

      saved++;
    }

    LOG.info("Profiles migration complete: {} upserted.", saved);
  }

  private void migrateSocialMediaLinks() {
    LOG.info("Migrating social media links...");
    List<Document> docs = readBsonFile("social_medias.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      Optional<SocialMediaLink> existing = socialMediaRepository.findByLegacyId(legacyId);

      if (existing.isPresent()) {
        SocialMediaLink updated = new SocialMediaLink(
            existing.get().id(),
            getString(doc, "type"),
            getString(doc, "link"),
            getString(doc, "name"),
            getBoolean(doc, "includeOnResume"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        socialMediaRepository.save(updated);
      } else {
        SocialMediaLink link = new SocialMediaLink(
            null,
            getString(doc, "type"),
            getString(doc, "link"),
            getString(doc, "name"),
            getBoolean(doc, "includeOnResume"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        socialMediaRepository.save(link);
      }

      saved++;
    }

    LOG.info("Social media links migration complete: {} upserted, {} skipped.",
        saved, skipped);
  }

  private void migrateJobs(final Map<String, String> skillIdMap) {
    LOG.info("Migrating jobs...");
    List<Document> docs = readBsonFile("jobs.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      List<String> skillIds = mapIds(getLegacyRefIds(doc, "skills"), skillIdMap);
      Optional<Job> existing = jobRepository.findByLegacyId(legacyId);

      if (existing.isPresent()) {
        Job updated = new Job(
            existing.get().id(),
            getString(doc, "title"),
            getString(doc, "company"),
            getString(doc, "companyUrl"),
            getString(doc, "companyImage"),
            getString(doc, "startDate"),
            getString(doc, "endDate"),
            getString(doc, "location"),
            getString(doc, "shortDescription"),
            getString(doc, "longDescription"),
            getBoolean(doc, "education"),
            getBoolean(doc, "includeOnResume"),
            skillIds,
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        jobRepository.save(updated);
      } else {
        Job job = new Job(
            null,
            getString(doc, "title"),
            getString(doc, "company"),
            getString(doc, "companyUrl"),
            getString(doc, "companyImage"),
            getString(doc, "startDate"),
            getString(doc, "endDate"),
            getString(doc, "location"),
            getString(doc, "shortDescription"),
            getString(doc, "longDescription"),
            getBoolean(doc, "education"),
            getBoolean(doc, "includeOnResume"),
            skillIds,
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        jobRepository.save(job);
      }

      saved++;
    }

    LOG.info("Jobs migration complete: {} upserted, {} skipped.", saved, skipped);
  }

  private void migrateBlogs(
      final Map<String, String> tagIdMap,
      final Map<String, String> skillIdMap
  ) {
    LOG.info("Migrating blogs...");
    List<Document> docs = readBsonFile("blogs.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      List<String> tagIds = mapIds(getLegacyRefIds(doc, "tags"), tagIdMap);
      List<String> skillIds = mapIds(getLegacyRefIds(doc, "skills"), skillIdMap);
      Optional<Blog> existing = blogRepository.findByLegacyId(legacyId);

      if (existing.isPresent()) {
        Blog updated = new Blog(
            existing.get().id(),
            getString(doc, "title"),
            getString(doc, "shortDescription"),
            getString(doc, "content"),
            getBoolean(doc, "published"),
            getString(doc, "featuredImage"),
            tagIds,
            skillIds,
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        blogRepository.save(updated);
      } else {
        Blog blog = new Blog(
            null,
            getString(doc, "title"),
            getString(doc, "shortDescription"),
            getString(doc, "content"),
            getBoolean(doc, "published"),
            getString(doc, "featuredImage"),
            tagIds,
            skillIds,
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        blogRepository.save(blog);
      }

      saved++;
    }

    LOG.info("Blogs migration complete: {} upserted, {} skipped.", saved, skipped);
  }

  private void migrateTourSteps() {
    LOG.info("Migrating tour steps...");
    List<Document> docs = readBsonFile("tour_steps.bson");
    int saved = 0;
    int skipped = 0;

    for (Document doc : docs) {
      String legacyId = extractLegacyId(doc);
      if (legacyId == null) {
        skipped++;
        continue;
      }

      Optional<TourStep> existing = tourStepRepository.findByLegacyId(legacyId);

      if (existing.isPresent()) {
        TourStep updated = new TourStep(
            existing.get().id(),
            getString(doc, "title"),
            getString(doc, "selector"),
            getString(doc, "description"),
            getString(doc, "titleImage"),
            getString(doc, "position"),
            getInt(doc, "order"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        tourStepRepository.save(updated);
      } else {
        TourStep step = new TourStep(
            null,
            getString(doc, "title"),
            getString(doc, "selector"),
            getString(doc, "description"),
            getString(doc, "titleImage"),
            getString(doc, "position"),
            getInt(doc, "order"),
            toInstant(doc.get("createdAt")),
            toInstant(doc.get("updatedAt")),
            legacyId
        );
        tourStepRepository.save(step);
      }

      saved++;
    }

    LOG.info("Tour steps migration complete: {} upserted, {} skipped.", saved, skipped);
  }

  private List<Document> readBsonFile(final String fileName) {
    Path filePath = Path.of(backupPath, fileName);
    List<Document> documents = new ArrayList<>();

    if (!filePath.toFile().exists()) {
      LOG.warn("BSON file not found, skipping: {}", filePath);
      return documents;
    }

    LOG.info("Reading BSON file: {}", filePath);
    DocumentCodec codec = new DocumentCodec();

    try (FileInputStream fis = new FileInputStream(filePath.toFile());
        FileChannel channel = fis.getChannel()) {

      long fileSize = channel.size();
      ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
      channel.read(buffer);
      buffer.flip();

      while (buffer.hasRemaining()) {
        int docStart = buffer.position();
        if (buffer.remaining() < 4) {
          break;
        }

        int docSize = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(docStart);
        if (docSize < 5 || docSize > buffer.remaining()) {
          LOG.warn(
              "Invalid BSON document size {} at position {} in {}, stopping parse.",
              docSize, docStart, fileName);
          break;
        }

        ByteBuffer docBuffer = buffer.slice(docStart, docSize);
        docBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.position(docStart + docSize);

        try (BsonBinaryReader reader = new BsonBinaryReader(docBuffer)) {
          Document doc = codec.decode(reader, DecoderContext.builder().build());
          documents.add(doc);
        } catch (Exception e) {
          LOG.warn("Failed to parse BSON document at position {} in {}: {}",
              docStart, fileName, e.getMessage());
        }
      }

    } catch (IOException e) {
      LOG.error("Failed to read BSON file {}: {}", filePath, e.getMessage());
    }

    LOG.info("Parsed {} documents from {}", documents.size(), fileName);
    return documents;
  }

  private String extractLegacyId(final Document doc) {
    Object id = doc.get("_id");
    if (id == null) {
      LOG.warn("Document has no _id field, skipping.");
      return null;
    }
    return id.toString();
  }

  private String getString(final Document doc, final String key) {
    Object value = doc.get(key);
    if (value == null) {
      return null;
    }
    return value.toString();
  }

  private Double getDouble(final Document doc, final String key) {
    Object value = doc.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number num) {
      return num.doubleValue();
    }
    try {
      return Double.parseDouble(value.toString());
    } catch (NumberFormatException e) {
      LOG.warn("Could not parse Double for key '{}', value '{}': {}",
          key, value, e.getMessage());
      return null;
    }
  }

  private int getInt(final Document doc, final String key) {
    Object value = doc.get(key);
    if (value instanceof Number num) {
      return num.intValue();
    }
    if (value != null) {
      try {
        return Integer.parseInt(value.toString());
      } catch (NumberFormatException e) {
        LOG.warn("Could not parse int for key '{}', value '{}': {}",
            key, value, e.getMessage());
      }
    }
    return 0;
  }

  private boolean getBoolean(final Document doc, final String key) {
    Object value = doc.get(key);
    if (value instanceof Boolean b) {
      return b;
    }
    if (value != null) {
      return Boolean.parseBoolean(value.toString());
    }
    return false;
  }

  private Instant toInstant(final Object value) {
    if (value == null) {
      return Instant.now();
    }
    if (value instanceof Date d) {
      return d.toInstant();
    }
    if (value instanceof Instant i) {
      return i;
    }
    if (value instanceof Long l) {
      return Instant.ofEpochMilli(l);
    }
    LOG.warn("Could not convert value to Instant: {} ({})",
        value, value.getClass().getSimpleName());
    return Instant.now();
  }

  /**
   * Extracts legacy reference IDs from a Strapi relation field. Strapi stores
   * relations as a list of objects with an "_id" field, or as a list of strings.
   */
  private List<String> getLegacyRefIds(final Document doc, final String key) {
    Object value = doc.get(key);
    List<String> ids = new ArrayList<>();
    if (value == null) {
      return ids;
    }
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Document refDoc) {
          Object refId = refDoc.get("_id");
          if (refId != null) {
            ids.add(refId.toString());
          }
        } else if (item instanceof String s) {
          ids.add(s);
        } else if (item != null) {
          ids.add(item.toString());
        }
      }
    }
    return ids;
  }

  private List<String> mapIds(
      final List<String> legacyIds,
      final Map<String, String> idMap
  ) {
    List<String> newIds = new ArrayList<>();
    for (String legacyId : legacyIds) {
      String newId = idMap.get(legacyId);
      if (newId != null) {
        newIds.add(newId);
      } else {
        LOG.warn("No mapping found for legacy ID: {}", legacyId);
      }
    }
    return newIds;
  }
}
