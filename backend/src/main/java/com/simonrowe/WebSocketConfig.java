package com.simonrowe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  /**
   * How often each side of a STOMP connection must write, in milliseconds.
   *
   * <p>This is not a tuning knob, it is what keeps the socket open at all. Every proxy between
   * the browser and this process closes an idle connection: nginx's {@code proxy_read_timeout}
   * defaults to 60s, Cloudflare's WebSocket idle timeout is around 100s, and the pinggy tunnel
   * has its own. A STOMP connection carrying no traffic looks exactly like a dead one to all
   * three.
   *
   * <p>A heartbeat is only negotiated if BOTH ends offer one. Spring's simple broker defaults to
   * {@code 0, 0} — heartbeats off — whenever no {@code TaskScheduler} is set, and it advertises
   * that zero in its CONNECTED frame, which makes the client disable its own outgoing heartbeat
   * however it is configured. So the server side below is the half that has to be right.
   */
  private static final long HEARTBEAT_MILLIS = 10_000L;

  @Value("${cors.allowed-origins:}")
  private String allowedOrigins;

  private TaskScheduler heartbeatScheduler;

  /**
   * Injected by name rather than declared here. {@code @EnableWebSocketMessageBroker} already
   * publishes a {@code messageBrokerTaskScheduler} bean for exactly this purpose; declaring a
   * second {@code TaskScheduler} bean would make Boot's own task-scheduling auto-configuration
   * back off ({@code @ConditionalOnMissingBean(TaskScheduler.class)}) and quietly move every
   * {@code @Scheduled} method in the application onto whichever pool was declared last.
   *
   * <p>{@code @Lazy} because this configurer is consulted while the broker configuration is
   * still being built, which is before that scheduler bean exists.
   */
  @Autowired
  public void setHeartbeatScheduler(
      @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
    this.heartbeatScheduler = heartbeatScheduler;
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry config) {
    config.enableSimpleBroker("/topic")
        .setTaskScheduler(heartbeatScheduler)
        // Stated explicitly even though setTaskScheduler already defaults it to these values.
        // The default is a side effect of a setter whose name says nothing about heartbeats,
        // and a future edit that moves the scheduler elsewhere would silently take the
        // heartbeat with it.
        .setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS});
    config.setApplicationDestinationPrefixes("/app");
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    if (!allowedOrigins.isBlank()) {
      registry.addEndpoint("/ws/chat")
          .setAllowedOrigins(allowedOrigins.split(","));
    } else {
      registry.addEndpoint("/ws/chat")
          .setAllowedOriginPatterns("*");
    }
  }
}
