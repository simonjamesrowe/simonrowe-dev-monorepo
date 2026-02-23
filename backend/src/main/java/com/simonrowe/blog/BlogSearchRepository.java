package com.simonrowe.blog;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface BlogSearchRepository extends ElasticsearchRepository<BlogSearchDocument, String> {
}
