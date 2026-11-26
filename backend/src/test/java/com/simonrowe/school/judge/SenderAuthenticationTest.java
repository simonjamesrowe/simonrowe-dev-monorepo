package com.simonrowe.school.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The header shapes are copied from real school mail, with the mailbox address replaced. */
class SenderAuthenticationTest {

  private static final String DOMAIN = "school.example";
  private static final List<String> TRUSTED = List.of("bounces+189724-*@sendgrid.net");

  private static final String BROADCAST = """
      mx.google.com;       dkim=pass header.i=@sendgrid.net header.s=smtpapi header.b=abc;\
             spf=pass (google.com: domain of bounces+189724-cabc-parent=example.com@sendgrid.net \
      designates 50.31.43.182 as permitted sender) \
      smtp.mailfrom="bounces+189724-cabc-parent=example.com@sendgrid.net";\
             dmarc=fail (p=NONE sp=NONE dis=NONE) header.from=school.example""";

  private static final String DIRECT = """
      mx.google.com;       dkim=pass header.i=@school.example header.s=mailprotect header.b=x;\
             spf=pass (google.com: domain of attendance@school.example designates 5.150.104.10 \
      as permitted sender) smtp.mailfrom=attendance@school.example;\
             dmarc=pass (p=NONE sp=NONE dis=NONE) header.from=school.example""";

  @Test
  void broadcastFromTheSchoolsSendGridAccountIsAuthenticated() {
    assertThat(SenderAuthentication.authenticated(BROADCAST, DOMAIN, TRUSTED)).isTrue();
  }

  @Test
  void mailPassingTheSchoolsOwnDmarcIsAuthenticated() {
    assertThat(SenderAuthentication.authenticated(DIRECT, DOMAIN, TRUSTED)).isTrue();
  }

  @Test
  void anotherSendGridCustomerClaimingTheSchoolIsNot() {
    final String forged = BROADCAST.replace("189724", "555555");

    assertThat(SenderAuthentication.authenticated(forged, DOMAIN, TRUSTED)).isFalse();
  }

  @Test
  void trustedEnvelopeThatFailedSpfIsNot() {
    final String failed = BROADCAST.replace("spf=pass", "spf=softfail");

    assertThat(SenderAuthentication.authenticated(failed, DOMAIN, TRUSTED)).isFalse();
  }

  @Test
  void dmarcPassForSomeoneElsesDomainIsNot() {
    final String other = DIRECT.replace("header.from=school.example", "header.from=evil.example")
        .replace("smtp.mailfrom=attendance@school.example", "smtp.mailfrom=a@evil.example");

    assertThat(SenderAuthentication.authenticated(other, DOMAIN, TRUSTED)).isFalse();
  }

  @Test
  void noHeaderIsNotAuthenticated() {
    assertThat(SenderAuthentication.authenticated(null, DOMAIN, TRUSTED)).isFalse();
  }

  @Test
  void onlyGooglesOwnHeaderIsRead() {
    // A sender can add Authentication-Results headers of its own claiming anything; Gmail lists
    // Google's first, and a header that does not start with Google's MX is ignored.
    final String claimed = "evil.example; dmarc=pass header.from=school.example";

    assertThat(SenderAuthentication.googleHeader(List.of(claimed))).isEmpty();
    assertThat(SenderAuthentication.googleHeader(List.of(BROADCAST, claimed))).contains(BROADCAST);
  }

  @Test
  void globsMatchPrefixAndSuffixWithoutOverlapping() {
    assertThat(SenderAuthentication.matchesGlob("bounces+1-x@sendgrid.net",
        "bounces+1-*@sendgrid.net")).isTrue();
    assertThat(SenderAuthentication.matchesGlob("bounces+1-@sendgrid.net.evil.example",
        "bounces+1-*@sendgrid.net")).isFalse();
    assertThat(SenderAuthentication.matchesGlob("ab", "ab*b")).isFalse();
    assertThat(SenderAuthentication.matchesGlob("a@b", "a@b")).isTrue();
  }
}
