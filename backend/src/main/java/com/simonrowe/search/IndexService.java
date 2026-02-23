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
import com.simonrowe.search.elasticsearch.BlogSearchDocument;
import com.simonrowe.search.elasticsearch.ElasticsearchConfig;
import com.simonrowe.search.elasticsearch.SiteSearchDocument;
import com.simonrowe.skills.Skill;
import com.simonrowe.skills.SkillGroup;
import com.simonrowe.skills.SkillGroupRepository;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  public IndexService(
      final ElasticsearchClient client,
      final BlogRepository blogRepository,
      final JobRepository jobRepository,
      final SkillGroupRepository skillGroupRepository
  ) {
    this.client = client;
    this.blogRepository = blogRepository;
    this.jobRepository = jobRepository;
    this.skillGroupRepository = skillGroupRepository;
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
    String imageUrl = blog.featuredImageUrl();
    return new SiteSearchDocument(
        blog.id(),
        blog.title(),
        "blog",
        blog.shortDescription(),
        null,
        imageUrl,
        "/blogs/" + blog.id()
    );
  }

  public SiteSearchDocument jobToSiteDocument(final Job job) {
    String imageUrl = job.companyImage() != null ? job.companyImage().url() : null;
    return new SiteSearchDocument(
        job.id(),
        job.title(),
        "job",
        job.shortDescription(),
        job.longDescription(),
        imageUrl,
        "/employment"
    );
  }

  public SiteSearchDocument skillToSiteDocument(final Skill skill, final String skillGroupId) {
    String imageUrl = skill.image() != null ? skill.image().url() : null;
    return new SiteSearchDocument(
        skillGroupId + "_" + skill.id(),
        skill.name(),
        "skill",
        skill.description(),
        null,
        imageUrl,
        "/skills"
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
        blog.featuredImageUrl(),
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
    List<SiteSearchDocument> skillDocs = skillGroups.stream()
        .flatMap(group -> group.skills() == null
            ? java.util.stream.Stream.empty()
            : group.skills().stream().map(skill -> skillToSiteDocument(skill, group.id())))
        .toList();
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

  public void indexSkillContent(final Skill skill, final String skillGroupId) throws IOException {
    indexSiteDocument(skillToSiteDocument(skill, skillGroupId));
  }

  public void deleteSkillContent(final String skillId) throws IOException {
    deleteSiteDocument(skillId);
  }
}
