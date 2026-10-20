package com.simonrowe.observability;

import com.simonrowe.admin.AdminBlogRepository;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.media.MediaAssetRepository;
import com.simonrowe.shortlink.ShortLinkRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean;

/** Adds query intent above driver commands, without recording arguments or results. */
final class RepositoryTracing implements BeanPostProcessor {

  private static final Map<Class<?>, String> COLLECTIONS = Map.of(
      BlogRepository.class, "blogs", AdminBlogRepository.class, "blogs",
      MediaAssetRepository.class, "media_assets", ShortLinkRepository.class, "short_links");

  private final ObjectProvider<ObservationRegistry> registries;

  RepositoryTracing(final ObjectProvider<ObservationRegistry> registries) {
    this.registries = registries;
  }

  @Override
  public Object postProcessBeforeInitialization(final Object bean, final String beanName) {
    if (bean instanceof MongoRepositoryFactoryBean<?, ?, ?> factoryBean) {
      factoryBean.addRepositoryFactoryCustomizer(factory ->
          factory.addRepositoryProxyPostProcessor((proxy, information) -> {
            Class<?> repository = information.getRepositoryInterface();
            String collection = COLLECTIONS.get(repository);
            if (collection != null) {
              proxy.addAdvice((MethodInterceptor) invocation ->
                  trace(invocation, repository.getSimpleName(), collection));
            }
          }));
    }
    return bean;
  }

  private Object trace(final MethodInvocation invocation, final String repository,
      final String collection) throws Throwable {
    String method = invocation.getMethod().getName();
    // Object/proxy infrastructure is not a database operation. Scope this pilot to reads.
    if (!(method.startsWith("find") || method.startsWith("count")
        || method.startsWith("exists"))) {
      return invocation.proceed();
    }
    String identity = repository + "." + method;
    Observation observation = Observation.createNotStarted("spring.data.repository",
            registries.getIfAvailable(() -> ObservationRegistry.NOOP))
        .contextualName(identity)
        .highCardinalityKeyValue("app.repository.method", identity)
        .highCardinalityKeyValue("app.query.name", collection + "." + method)
        .highCardinalityKeyValue("db.collection.name", collection)
        .start();
    try (Observation.Scope ignored = observation.openScope()) {
      Object result = invocation.proceed();
      observation.highCardinalityKeyValue("app.outcome", "success");
      return result;
    } catch (Throwable failure) {
      observation.highCardinalityKeyValue("app.outcome", "failed");
      observation.error(failure);
      throw failure;
    } finally {
      observation.stop();
    }
  }
}
