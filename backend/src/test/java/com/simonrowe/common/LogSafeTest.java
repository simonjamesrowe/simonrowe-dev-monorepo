package com.simonrowe.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSafeTest {

  @Test
  void leavesAnOrdinaryIdentifierAlone() {
    assertEquals("507f1f77bcf86cd799439011",
        LogSafe.value("507f1f77bcf86cd799439011"));
  }

  @Test
  void replacesNewlinesSoOneValueCannotBecomeTwoLogLines() {
    String forged = "abc\nINFO  c.s.a.AdminBlogController - Deleted blog: id=real";

    String sanitised = LogSafe.value(forged);

    assertFalse(sanitised.contains("\n"));
    assertTrue(sanitised.startsWith("abc_INFO"));
  }

  @Test
  void replacesCarriageReturnsAndTabsToo() {
    assertEquals("a_b_c", LogSafe.value("a\rb\tc"));
  }

  @Test
  void truncatesAnUnboundedValue() {
    String sanitised = LogSafe.value("x".repeat(1000));

    assertEquals(259, sanitised.length());
    assertTrue(sanitised.endsWith("..."));
  }

  @Test
  void keepsValueAtTheLimitIntact() {
    String atLimit = "x".repeat(256);

    assertEquals(atLimit, LogSafe.value(atLimit));
  }

  @Test
  void rendersNullWithoutThrowing() {
    assertEquals("null", LogSafe.value(null));
  }
}
