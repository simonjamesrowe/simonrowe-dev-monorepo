package com.simonrowe.platform;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Assembles the status payload and the changelog.
 *
 * <p><b>Nothing reachable from here may call an LLM.</b> Summaries are written at ingest by
 * {@code ReleaseSummarySweep}; this class only reads what is stored.
 */
@Service
public class PlatformStatusService {

  /** Hard ceiling on the changelog page size, so a crafted request cannot dump the collection. */
  static final int MAX_LIMIT = 100;

  private final RunningVersion runningVersion;
  private final FactoryVersionClient factoryVersionClient;
  private final ProdImageCatalog imageCatalog;
  private final PlatformReleaseRepository releaseRepository;

  /**
   * Creates the service.
   *
   * @param runningVersion this process's own version
   * @param factoryVersionClient client for the sibling services' versions
   * @param imageCatalog the third-party image catalog
   * @param releaseRepository the stored releases
   */
  public PlatformStatusService(
      final RunningVersion runningVersion,
      final FactoryVersionClient factoryVersionClient,
      final ProdImageCatalog imageCatalog,
      final PlatformReleaseRepository releaseRepository) {
    this.runningVersion = runningVersion;
    this.factoryVersionClient = factoryVersionClient;
    this.imageCatalog = imageCatalog;
    this.releaseRepository = releaseRepository;
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

  /**
   * The changelog, newest first.
   *
   * @param limit how many entries to return; clamped to {@link #MAX_LIMIT}
   * @return the entries; empty when nothing has been seeded yet
   */
  public List<ReleaseResponse> releases(final int limit) {
    String runningSha = runningVersion.commit();
    return releaseRepository.findRecent(Math.min(limit, MAX_LIMIT)).stream()
        .map(release -> ReleaseResponse.from(release, runningSha))
        .toList();
  }
}
