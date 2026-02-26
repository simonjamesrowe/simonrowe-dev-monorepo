package com.simonrowe;

import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Configuration
public class TestJwtDecoderConfig {

  @Bean
  public JwtDecoder jwtDecoder() {
    return token -> new Jwt(
        token,
        Instant.now(),
        Instant.now().plusSeconds(3600),
        Map.of("alg", "none"),
        Map.of("sub", "test-user")
    );
  }
}
