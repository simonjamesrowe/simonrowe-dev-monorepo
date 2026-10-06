package com.simonrowe.platform;

import com.simonrowe.blog.BlogSearchRepository;
import java.lang.reflect.Proxy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Stand-ins for the beans that contact a datastore while being <em>created</em>, active only in
 * the AOT cache training run that {@code bootBuildImage} performs at image build time.
 *
 * <p>That run refreshes the whole context with {@code -Dspring.context.exit=onRefresh} inside
 * the buildpack, where no datastore exists, and the build fails if the refresh does. Most beans
 * only connect when started, which the run never reaches. The exceptions are listed here.
 *
 * <p><b>Never activate the {@code aot-training} profile anywhere else.</b> Publishing a second
 * {@link VectorStore} is exactly what makes Spring AI's Elasticsearch auto-configuration back
 * off (it is {@code @ConditionalOnMissingBean(VectorStore)}), which is the point here and would
 * silently replace the site's real search index with an in-memory one in a running app.
 */
@Configuration(proxyBeanMethods = false)
@Profile("aot-training")
public class AotTrainingConfiguration {

  /**
   * An in-memory store in place of Elasticsearch's. Spring AI's
   * {@code ElasticsearchVectorStore.afterPropertiesSet()} always asks Elasticsearch whether the
   * index exists, and throws when it cannot; {@code initialize-schema: false} does not avoid
   * that call, it only changes what happens after it.
   *
   * @param embeddingModel the configured embedding model; building it makes no request
   * @return a store that holds nothing and is never queried
   */
  @Bean
  public VectorStore aotTrainingVectorStore(final EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
  }

  /**
   * An inert stand-in for the one Spring Data Elasticsearch repository. Creating the real one
   * asks Elasticsearch whether the {@code @Document}'s index exists, so the training profile
   * switches repository scanning off and supplies this instead. Any call fails loudly, which
   * the training run never makes.
   *
   * @return a proxy implementing the repository interface
   */
  @Bean
  public BlogSearchRepository aotTrainingBlogSearchRepository() {
    return (BlogSearchRepository) Proxy.newProxyInstance(
        BlogSearchRepository.class.getClassLoader(),
        new Class<?>[] {BlogSearchRepository.class},
        (proxy, method, args) -> {
          throw new UnsupportedOperationException(
              "BlogSearchRepository is a stand-in in the aot-training profile");
        });
  }
}
