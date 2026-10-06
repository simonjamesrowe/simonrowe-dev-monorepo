package com.simonrowe.platform;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Assembles the status payload. The changelog is {@link ReleaseQueryService}'s.
 *
 * <p><b>Nothing reachable from here may call an LLM.</b> Summaries are written at ingest by
 * {@code ReleaseSummarySweep}; this class only reads what is stored.
 */
@Service
public class PlatformStatusService {

  private final RunningVersion runningVersion;
  private final FactoryVersionClient factoryVersionClient;
  private final ProdImageCatalog imageCatalog;

  /**
   * Creates the service.
   *
   * @param runningVersion this process's own version
   * @param factoryVersionClient client for the sibling services' versions
   * @param imageCatalog the third-party image catalog
   */
  public PlatformStatusService(
      final RunningVersion runningVersion,
      final FactoryVersionClient factoryVersionClient,
      final ProdImageCatalog imageCatalog) {
    this.runningVersion = runningVersion;
    this.factoryVersionClient = factoryVersionClient;
    this.imageCatalog = imageCatalog;
  }

  /**
   * What is running right now.
   *
   * @return the status; the backend is always first and always reachable
   */
  public PlatformStatusResponse status() {
    List<ServiceVersion> services = new ArrayList<>();
    services.add(runningVersion.current());
    services.addAll(factoryVersionClient.versions());
    return new PlatformStatusResponse(List.copyOf(services), imageCatalog.components());
  }
}
