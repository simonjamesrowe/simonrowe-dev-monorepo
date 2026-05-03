package com.simonrowe;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

public final class AdminTestAuth {

  public static final SimpleGrantedAuthority ADMIN_AUTHORITY =
      new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN");

  private AdminTestAuth() {
  }

  public static JwtRequestPostProcessor adminJwt() {
    return jwt().authorities(ADMIN_AUTHORITY);
  }
}
