package com.simonrowe.school.chat;

/**
 * One school chat turn, sent over STOMP.
 *
 * @param sessionId the browser's session id, which also names the reply topic
 * @param message the question
 * @param yearGroups the selected year groups, empty or null for no filter. A parent with
 *     children in different years selects several
 * @param accessToken an Auth0 access token, or null for an anonymous turn. Carried in the body
 *     because a STOMP SEND frame has no practical place for an Authorization header that Spring
 *     Security will read. It is <b>validated server-side</b> by the same {@code JwtDecoder} the
 *     HTTP filter chain uses — never trusted as-is, and never used for anything but resolving
 *     the audience
 */
public record SchoolChatRequest(
    String sessionId,
    String message,
    java.util.List<String> yearGroups,
    String accessToken) {
}
