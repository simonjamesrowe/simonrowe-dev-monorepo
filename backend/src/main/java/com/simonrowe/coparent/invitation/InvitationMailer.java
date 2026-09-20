package com.simonrowe.coparent.invitation;

import com.simonrowe.coparent.config.CoparentProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends a plain invitation whose reusable token stays in the URL fragment. */
@Component
public class InvitationMailer {

  private final JavaMailSender mailSender;
  private final CoparentProperties properties;

  public InvitationMailer(
      final JavaMailSender mailSender,
      final CoparentProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  /** Attempts delivery when explicitly enabled; the caller retains the stored invitation. */
  public boolean send(
      final String recipient,
      final String inviterName,
      final String familyName,
      final String role,
      final String token) {
    if (!properties.emailEnabled()) {
      return true;
    }
    final String url = properties.appUrl() + "/invitations/accept#token=" + token;
    final SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(properties.emailFrom());
    message.setTo(recipient);
    message.setSubject("You've been invited to join " + familyName + " on CoParent");
    message.setText("""
        %s has invited you to join "%s" on CoParent as a %s.

        Accept the invitation: %s

        This invitation expires in 7 days.
        """.formatted(inviterName, familyName, role, url));
    try {
      mailSender.send(message);
      return true;
    } catch (MailException exception) {
      return false;
    }
  }
}
