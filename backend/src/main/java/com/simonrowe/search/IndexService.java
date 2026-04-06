package com.simonrowe.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.blog.Tag;
import com.simonrowe.employment.Job;
import com.simonrowe.employment.JobRepository;
import com.simonrowe.media.MediaVariantResolver;
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.ElasticsearchConfig;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import com.simonrowe.skills.SkillRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IndexService {

  private static final Logger LOG = LoggerFactory.getLogger(IndexService.class);

  private final ElasticsearchClient client;
  private final BlogRepository blogRepository;
  private final JobRepository jobRepository;
  private final SkillGroupRepository skillGroupRepository;
  private final SkillRepository skillRepository;
  private final MediaVariantResolver mediaVariantResolver;

  public IndexService(
      final ElasticsearchClient client,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillGroupRepository skillGroupRepository,
      final SkillRepository skillRepository,
      final MediaVariantResolver mediaVariantResolver
  ) {
    this.client = client;
    this.blogRepository = blogRepository;
    this.jobRepository = jobRepository;
    this.skillGroupRepository = skillGroupRepository;
    this.skillRepository = skillRepository;
    this.mediaVariantResolver = mediaVariantResolver;
  }

  public void indexSiteDocument(final SiteSearchDocument document) throws IOException {
    client.index(i -> i
        .index(ElasticsearchConfig.SITE_SEARCH_INDEX)
        .id(document.id())
        .document(document));
  }

  public void indexBlogDocument(final BlogSearchDocument document) throws IOException {
    client.index(i -> i
        .index(ElasticsearchConfig.BLOG_SEARCH_INDEX)
        .id(document.id())
        .document(document));
  }

  public void deleteSiteDocument(final String id) throws IOException {
    client.delete(d -> d
        .index(ElasticsearchConfig.SITE_SEARCH_INDEX)
        .id(id));
  }

  public void deleteBlogDocument(final String id) throws IOException {
    client.delete(d -> d
        .index(ElasticsearchConfig.BLOG_SEARCH_INDEX)
        .id(id));
  }

  public void bulkIndexSiteDocuments(final List<SiteSearchDocument> documents) throws IOException {
    if (documents.isEmpty()) {
      return;
    }
    BulkRequest.Builder builder = new BulkRequest.Builder();
    for (SiteSearchDocument doc : documents) {
      builder.operations(op -> op
          .index(idx -> idx
              .index(ElasticsearchConfig.SITE_SEARCH_INDEX)
              .id(doc.id())
              .document(doc)));
    }
    BulkResponse response = client.bulk(builder.build());
    if (response.errors()) {
      LOG.error("Bulk index to {} had errors", ElasticsearchConfig.SITE_SEARCH_INDEX);
    }
  }

  public void bulkIndexBlogDocuments(final List<BlogSearchDocument> documents) throws IOException {
    if (documents.isEmpty()) {
      return;
    }
    BulkRequest.Builder builder = new BulkRequest.Builder();
    for (BlogSearchDocument doc : documents) {
      builder.operations(op -> op
          .index(idx -> idx
              .index(ElasticsearchConfig.BLOG_SEARCH_INDEX)
              .id(doc.id())
              .document(doc)));
    }
    BulkResponse response = client.bulk(builder.build());
    if (response.errors()) {
      LOG.error("Bulk index to {} had errors", ElasticsearchConfig.BLOG_SEARCH_INDEX);
    }
  }

  public SiteSearchDocument blogToSiteDocument(final Blog blog) {
    String imageUrl = mediaVariantResolver.resolvePath(
        blog.featuredImageUrl(), "thumbnail", "small", "medium");
    return new SiteSearchDocument(
        blog.id(),
        blog.title(),
        "blog",
        blog.shortDescription(),
        null,
        null,
        imageUrl,
        "/blogs/" + blog.id(),
        blog.createdDate()
    );
  }

  public SiteSearchDocument jobToSiteDocument(final Job job) {
    String imageUrl = job.companyImage() != null
        ? mediaVariantResolver.resolvePath(
            job.companyImage().url(), "thumbnail", "small", "medium")
        : null;
    return new SiteSearchDocument(
        job.id(),
        job.title(),
        "job",
        job.shortDescription(),
        job.longDescription(),
        job.company(),
        imageUrl,
        "/jobs/" + job.id(),
        parseDate(job.startDate())
    );
  }

  public SiteSearchDocument skillToSiteDocument(
      final Skill skill, final String skillGroupId, final int skillIndex) {
    String imageUrl = skill.image() != null
        ? mediaVariantResolver.resolvePath(
            skill.image().url(), "thumbnail", "small", "medium")
        : null;
    Instant syntheticDate = Instant.parse("2026-01-01T00:00:00Z")
        .minusSeconds((long) skillIndex * 86400);
    return new SiteSearchDocument(
        skillGroupId + "_" + skill.id(),
        skill.name(),
        "skill",
        skill.description(),
        null,
        null,
        imageUrl,
        "/skills-groups/" + skillGroupId,
        syntheticDate
    );
  }

  public BlogSearchDocument blogToBlogDocument(final Blog blog) {
    List<String> tagNames = blog.tags() == null
        ? List.of()
        : blog.tags().stream().map(Tag::name).toList();

    List<String> skillNames = blog.skills() == null
        ? List.of()
        : blog.skills().stream()
            .map(com.simonrowe.blog.Skill::name).toList();

    return new BlogSearchDocument(
        blog.id(),
        blog.title(),
        blog.shortDescription(),
        blog.content(),
        tagNames,
        skillNames,
        mediaVariantResolver.resolvePath(
            blog.featuredImageUrl(), "thumbnail", "small", "medium"),
        blog.createdDate(),
        "/blogs/" + blog.id()
    );
  }

  public void fullSyncSiteIndex() throws IOException {
    LOG.info("Starting full sync of site_search index");
    Set<String> indexedIds = new HashSet<>();

    List<Blog> blogs = blogRepository.findByPublishedTrueOrderByCreatedDateDesc();
    List<SiteSearchDocument> blogDocs = blogs.stream()
        .map(this::blogToSiteDocument)
        .toList();
    bulkIndexSiteDocuments(blogDocs);
    blogDocs.forEach(doc -> indexedIds.add(doc.id()));

    List<Job> jobs = jobRepository.findAllByOrderByStartDateDesc();
    List<SiteSearchDocument> jobDocs = jobs.stream()
        .map(this::jobToSiteDocument)
        .toList();
    bulkIndexSiteDocuments(jobDocs);
    jobDocs.forEach(doc -> indexedIds.add(doc.id()));

    List<SkillGroup> skillGroups = skillGroupRepository.findAllByOrderByDisplayOrderAsc();
    List<String> allSkillIds = skillGroups.stream()
        .filter(g -> g.skills() != null)
        .flatMap(g -> g.skills().stream())
        .distinct()
        .toList();
    Map<String, Skill> skillMap = skillRepository.findAllByIdIn(allSkillIds).stream()
        .collect(Collectors.toMap(Skill::id, s -> s));
    List<SiteSearchDocument> skillDocs = new ArrayList<>();
    for (SkillGroup group : skillGroups) {
      if (group.skills() == null) {
        continue;
      }
      List<Skill> sortedSkills = group.skills().stream()
          .filter(skillMap::containsKey)
          .map(skillMap::get)
          .sorted(Comparator.comparingInt(
              s -> s.displayOrder() != null ? s.displayOrder() : Integer.MAX_VALUE))
          .toList();
      for (int i = 0; i < sortedSkills.size(); i++) {
        skillDocs.add(skillToSiteDocument(sortedSkills.get(i), group.id(), i));
      }
    }
    bulkIndexSiteDocuments(skillDocs);
    skillDocs.forEach(doc -> indexedIds.add(doc.id()));

    cleanupOrphans(ElasticsearchConfig.SITE_SEARCH_INDEX, indexedIds);
    LOG.info("Full sync of site_search completed: {} documents indexed",
        indexedIds.size());
  }

  public void fullSyncBlogIndex() throws IOException {
    LOG.info("Starting full sync of blog_search index");
    Set<String> indexedIds = new HashSet<>();

    List<Blog> blogs = blogRepository.findByPublishedTrueOrderByCreatedDateDesc();
    List<BlogSearchDocument> blogDocs = blogs.stream()
        .map(this::blogToBlogDocument)
        .toList();
    bulkIndexBlogDocuments(blogDocs);
    blogDocs.forEach(doc -> indexedIds.add(doc.id()));

    cleanupOrphans(ElasticsearchConfig.BLOG_SEARCH_INDEX, indexedIds);
    LOG.info("Full sync of blog_search completed: {} documents indexed",
        indexedIds.size());
  }

  private void cleanupOrphans(final String indexName, final Set<String> validIds)
      throws IOException {
    Set<String> existingIds = getAllDocumentIds(indexName);
    existingIds.removeAll(validIds);
    if (existingIds.isEmpty()) {
      return;
    }
    LOG.info("Removing {} orphan documents from {}", existingIds.size(), indexName);
    BulkRequest.Builder builder = new BulkRequest.Builder();
    for (String orphanId : existingIds) {
      builder.operations(op -> op
          .delete(d -> d.index(indexName).id(orphanId)));
    }
    client.bulk(builder.build());
  }

  private Set<String> getAllDocumentIds(final String indexName) throws IOException {
    Set<String> ids = new HashSet<>();
    SearchResponse<Map> response = client.search(s -> s
            .index(indexName)
            .size(10000)
            .source(src -> src.fetch(false)),
        Map.class);
    response.hits().hits().forEach(hit -> ids.add(hit.id()));
    return ids;
  }

  public void indexBlogContent(final Blog blog) throws IOException {
    indexSiteDocument(blogToSiteDocument(blog));
    indexBlogDocument(blogToBlogDocument(blog));
  }

  public void deleteBlogContent(final String blogId) throws IOException {
    deleteSiteDocument(blogId);
    deleteBlogDocument(blogId);
  }

  public void indexJobContent(final Job job) throws IOException {
    indexSiteDocument(jobToSiteDocument(job));
  }

  public void deleteJobContent(final String jobId) throws IOException {
    deleteSiteDocument(jobId);
  }

  public void indexSkillContent(
      final Skill skill, final String skillGroupId, final int skillIndex) throws IOException {
    indexSiteDocument(skillToSiteDocument(skill, skillGroupId, skillIndex));
  }

  public void deleteSkillContent(final String skillId) throws IOException {
    deleteSiteDocument(skillId);
  }

  private Instant parseDate(final String dateStr) {
    if (dateStr == null || dateStr.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant();
    } catch (java.time.format.DateTimeParseException e) {
      return java.time.YearMonth.parse(dateStr)
          .atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
  }
}
