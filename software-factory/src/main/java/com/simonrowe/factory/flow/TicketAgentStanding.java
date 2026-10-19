package com.simonrowe.factory.flow;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Which open tickets the ticket agent holds, and which it handed back stuck, for the Linear
 * drawer.
 *
 * <p>Cached for a minute. The drawer refreshes every 15 seconds while it is open, and this is two
 * Linear reads; uncached, an open drawer alone would spend most of the API key's hourly allowance.
 * A failed read answers "nothing known" and is not cached, so the drawer simply offers no buttons
 * until Linear answers again.
 */
@Component
public class TicketAgentStanding {

  static final Duration TTL = Duration.ofMinutes(1);

  private static final Logger LOG = LoggerFactory.getLogger(TicketAgentStanding.class);

  private final LinearGateway linear;
  private final LinearProperties linearProperties;
  private final TicketAgentProperties properties;
  private final Clock clock;
  private Map<String, String> cached = Map.of();
  private Instant cachedAt = Instant.EPOCH;

  @Autowired
  public TicketAgentStanding(
      final LinearGateway linear,
      final LinearProperties linearProperties,
      final TicketAgentProperties properties) {
    this(linear, linearProperties, properties, Clock.systemUTC());
  }

  TicketAgentStanding(
      final LinearGateway linear,
      final LinearProperties linearProperties,
      final TicketAgentProperties properties,
      final Clock clock) {
    this.linear = linear;
    this.linearProperties = linearProperties;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * Identifier to standing, {@link FlowDetail.Item#WITH_AGENT} or {@link FlowDetail.Item#STUCK}.
   *
   * @return the map; empty when the agent or Linear is off, or Linear could not be read
   */
  public synchronized Map<String, String> byIdentifier() {
    if (!properties.enabled() || !linearProperties.enabled()) {
      return Map.of();
    }
    Instant now = clock.instant();
    if (now.isBefore(cachedAt.plus(TTL))) {
      return cached;
    }
    try {
      Map<String, String> standing = new HashMap<>();
      record(standing, linear.openTicketsLabelled(properties.stuckLabel()), FlowDetail.Item.STUCK);
      record(standing, linear.openTicketsLabelled(properties.label()), FlowDetail.Item.WITH_AGENT);
      cached = Map.copyOf(standing);
      cachedAt = now;
      return cached;
    } catch (LinearApiException exception) {
      LOG.warn("Could not read which tickets the agent holds", exception);
      return Map.of();
    }
  }

  /** Later calls win, so a ticket labelled for the agent again reads as with it, not stuck. */
  private static void record(
      final Map<String, String> standing, final List<LinearTicket> tickets, final String value) {
    for (LinearTicket ticket : tickets) {
      standing.put(ticket.identifier(), value);
    }
  }
}
