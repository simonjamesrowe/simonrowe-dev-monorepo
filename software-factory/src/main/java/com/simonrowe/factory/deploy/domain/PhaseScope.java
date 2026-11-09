package com.simonrowe.factory.deploy.domain;

import java.util.List;

/**
 * Narrows a shell phase to part of the stack.
 *
 * @param services passed to the script as {@code SERVICES}; never empty, because the script reads
 *     an empty value as "use the default", which is every image
 * @param restartNginx passed as {@code RESTART_NGINX}; false only when nothing nginx serves
 *     changed
 */
public record PhaseScope(List<String> services, boolean restartNginx) {

  public PhaseScope {
    services = services == null ? List.of() : List.copyOf(services);
    if (services.isEmpty()) {
      throw new IllegalArgumentException("a phase scope must name at least one service");
    }
  }
}
