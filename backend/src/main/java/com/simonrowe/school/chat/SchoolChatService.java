package com.simonrowe.school.chat;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import com.simonrowe.school.retrieval.SchoolAudience;
import com.simonrowe.school.retrieval.SchoolRetrievalService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Runs one school chat turn.
 *
 * <p>Builds a fresh {@link SchoolTools} per request bound to the caller's audience, which is what
 * makes the tool boundary real: there is no shared tool instance that could be reached with the
 * wrong tier, and the audience is never a parameter the model or the client can influence.
 *
 * <p>Model options are set here rather than in {@code application.yml}. Anything under
 * {@code spring.ai.openai.chat} is merged into every per-call {@code OpenAiChatOptions} in the
 * whole application — that merge is why {@code reasoning-effort} is banned from the yml, and the
 * same applies to the model and the prompt cache key, which must differ between this assistant and
 * the portfolio one.
 *
 * <p>Taking the {@code ChatClient.Builder} rather than the configured {@code ChatClient} bean is
 * what makes those per-assistant options possible, and it also means this class inherits
 * <b>none</b> of the portfolio chat's default advisors. Conversation memory is the one that was
 * missed: Term Time shipped answering every turn from a blank slate, so a visitor answering the
 * assistant's own follow-up question found it had forgotten what it asked. It is attached
 * explicitly in {@link #remembering}, and only where there is a session id to key it on.
 */
@Service
public class SchoolChatService {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolChatService.class);

  /**
   * Routing hint for OpenAI's prompt cache. Distinct from the portfolio chat's, because the two
   * have completely different prefixes and sharing a key works against both.
   */
  private static final String PROMPT_CACHE_KEY = "term-time-v1";

  private final ChatClient.Builder chatClientBuilder;
  private final SchoolQueryService queries;
  private final SchoolRetrievalService retrieval;
  private final SchoolTopicGuardrail guardrail;
  private final SchoolProperties properties;
  private final SchoolBudget budget;
  private final SchoolUsageRecorder usageRecorder;
  private final ChatMemory chatMemory;

  public SchoolChatService(
      final ChatClient.Builder chatClientBuilder,
      final SchoolQueryService queries,
      final SchoolRetrievalService retrieval,
      final SchoolTopicGuardrail guardrail,
      final SchoolProperties properties,
      final SchoolBudget budget,
      final SchoolUsageRecorder usageRecorder,
      // Qualified, never by type. The portfolio chat's ChatMemory is @Primary and unbounded,
      // and taking it here would pool an unauthenticated endpoint's conversations into a store
      // nothing caps.
      @Qualifier("schoolChatMemory") final ChatMemory chatMemory) {
    this.chatClientBuilder = chatClientBuilder;
    this.queries = queries;
    this.retrieval = retrieval;
    this.guardrail = guardrail;
    this.properties = properties;
    this.budget = budget;
    this.usageRecorder = usageRecorder;
    this.chatMemory = chatMemory;
  }

  /**
   * Clears one conversation's history.
   *
   * <p>{@code BoundedChatMemoryRepository} expires conversations on its own, so this is not
   * required for correctness — it is here so a caller that knows a session is finished can say
   * so rather than leaving it to time out.
   *
   * @param sessionId the conversation to forget; null and blank are ignored
   */
  public void forget(final String sessionId) {
    if (sessionId != null && !sessionId.isBlank()) {
      chatMemory.clear(sessionId);
    }
  }

  /**
   * Answers a question.
   *
   * @param question what was asked
   * @param yearGroups the selected year groups, empty for none
   * @param audience which tiers this request may read
   * @return the answer
   */
  public SchoolChatResponse answer(
      final String question, final List<String> yearGroups, final SchoolAudience audience) {

    if (!audience.authenticated() && !budget.tryConsume()) {
      // Deliberately not an error status: from the visitor's point of view this is the service
      // being quiet, not their request being wrong, and a 500 would look like a bug.
      return SchoolChatResponse.unavailable(
          "Term Time has answered as many questions as it can today. Please try again tomorrow, "
              + "or check https://www.kilmorieschool.co.uk directly.");
    }

    if (!guardrail.isAboutSchool(question)) {
      return SchoolChatResponse.declined(
          "I can only help with questions about Kilmorie Primary School - term dates, events, "
              + "clubs, lunches and school arrangements.");
    }

    final SchoolTools tools =
        new SchoolTools(queries, retrieval, audience, yearGroups, null, null,
            properties.publicBaseUrl());
    final String context = yearGroups == null || yearGroups.isEmpty()
        ? "The visitor has not said which year group they are asking about."
        : "The visitor is asking about " + String.join(" and ", yearGroups) + ".";

    final org.springframework.ai.chat.model.ChatResponse raw;
    try {
      raw = chatClientBuilder.build()
          .prompt()
          // Spring AI 2.0 takes the BUILDER here, not a built options object.
          .options(OpenAiChatOptions.builder()
              .model(properties.chatModel())
              // REQUIRED, not a tuning knob. gpt-5.6-luna is a reasoning model, and OpenAI
              // rejects function tools alongside a reasoning effort on /v1/chat/completions:
              // "Function tools with reasoning_effort are not supported ... set reasoning_effort
              // to 'none'". Every turn 400s without this, because this assistant is entirely
              // tool-driven. Verified live against the API, not inferred — the same family of
              // trap that bans reasoning-effort from application.yml, arriving from the model's
              // own default rather than from configuration.
              .reasoningEffort("none")
              .promptCacheKey(PROMPT_CACHE_KEY))
          .system(SchoolSystemPrompt.TEXT)
          .tools(tools)
          .user(context + "\n\nQuestion: " + question)
          .call()
          .chatResponse();
    } catch (RuntimeException e) {
      LOG.warn("School chat turn failed: {}", e.getMessage());
      return SchoolChatResponse.unavailable(
          "Something went wrong answering that. Please try again.");
    }

    final String answer = raw == null || raw.getResult() == null
        || raw.getResult().getOutput() == null
        ? null
        : raw.getResult().getOutput().getText();
    recordChatUsage(raw, null, null);

    if (answer == null || answer.isBlank()) {
      return SchoolChatResponse.unavailable("I could not produce an answer to that.");
    }

    return SchoolChatResponse.answered(answer);
  }

  /**
   * Streams an answer, publishing tool activity as it happens.
   *
   * <p>Shares every guard with {@link #answer}: the budget, the topic gate, and — on the
   * terminal frame — the output-side name check. The name check cannot run per chunk, because a
   * name may straddle two chunks and because withholding half a streamed answer leaves the
   * reader with the first half. It runs once on the assembled text, and a failure replaces the
   * whole answer.
   *
   * @param question what was asked
   * @param yearGroups the selected year groups, empty for none
   * @param audience which tiers this request may read
   * @param sessionId the topic tool frames are published to
   * @param publisher the tool-frame publisher
   * @param clientAddress the caller's address, hashed before storage, or null
   * @return a stream of text chunks; tool frames go out of band through the publisher
   */
  public Flux<String> stream(
      final String question, final List<String> yearGroups, final SchoolAudience audience,
      final String sessionId, final SchoolStreamPublisher publisher,
      final String clientAddress) {

    if (!audience.authenticated() && !budget.tryConsume()) {
      return Flux.just("Term Time has answered as many questions as it can today. Please try "
          + "again tomorrow, or check https://www.kilmorieschool.co.uk directly.");
    }
    if (!guardrail.isAboutSchool(question)) {
      return Flux.just("I can only help with questions about Kilmorie Primary School - term "
          + "dates, events, clubs, lunches and school arrangements.");
    }

    final SchoolTools tools =
        new SchoolTools(queries, retrieval, audience, yearGroups, publisher, sessionId,
            properties.publicBaseUrl());
    final String context = yearGroups == null || yearGroups.isEmpty()
        ? "The visitor has not said which year group they are asking about."
        : "The visitor is asking about " + String.join(" and ", yearGroups) + ".";

    return remembering(chatClientBuilder.build()
        .prompt()
        .options(OpenAiChatOptions.builder()
            .model(properties.chatModel())
            // See answer(): a reasoning model rejects function tools unless this is 'none',
            // and this assistant is entirely tool-driven.
            .reasoningEffort("none")
            .promptCacheKey(PROMPT_CACHE_KEY))
        .system(SchoolSystemPrompt.TEXT)
        .tools(tools)
        .user(context + "\n\nQuestion: " + question), sessionId)
        .stream()
        .chatResponse()
        // Usage only appears on the terminal chunk of a stream, so cost is recorded as the
        // frames go past rather than at the end — the earlier chunks carry no usage at all and
        // recordChatUsage ignores them.
        .doOnNext(response -> recordChatUsage(response, sessionId, clientAddress))
        .map(response -> response.getResult() == null || response.getResult().getOutput() == null
            ? ""
            : java.util.Objects.requireNonNullElse(
                response.getResult().getOutput().getText(), ""))
        .onErrorResume(e -> {
          LOG.warn("School chat stream failed: {}", e.getMessage());
          return Flux.just("Something went wrong answering that. Please try again.");
        });
  }

  /**
   * Attaches conversation memory to a request, when there is a conversation to attach it to.
   *
   * <p>Applied on the streaming path only, because that is the only one carrying a session id.
   * {@link #answer} has none — {@code POST /api/school/chat} takes a bare question — and adding
   * the advisor without one would be actively unsafe rather than merely useless:
   * {@code MessageChatMemoryAdvisor} falls back to a single default conversation id, so every
   * anonymous caller of that endpoint would share one history and read each other's questions.
   * A blank session id on the stream path is treated the same way, for the same reason.
   *
   * @param request the prompt under construction
   * @param sessionId the conversation id, or null/blank for a one-shot turn
   * @return the request, with memory attached where a conversation id was supplied
   */
  ChatClient.ChatClientRequestSpec remembering(
      final ChatClient.ChatClientRequestSpec request, final String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return request;
    }
    return request
        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId));
  }

  /**
   * Records what a chat turn actually cost, from the provider's own numbers.
   *
   * <p>Reads {@code cacheReadInputTokens} where the provider supplies it: OpenAI bills cached
   * prompt tokens at a tenth, and ignoring that overstates the cost of every turn after the
   * first by roughly the whole system prompt.
   *
   * @param response the completed response, possibly null
   * @param sessionId the chat session, or null
   * @param clientAddress the caller's address, hashed before storage, or null
   */
  void recordChatUsage(final org.springframework.ai.chat.model.ChatResponse response,
      final String sessionId, final String clientAddress) {
    if (response == null || response.getMetadata() == null
        || response.getMetadata().getUsage() == null) {
      return;
    }
    final var usage = response.getMetadata().getUsage();
    final Long cached = usage.getCacheReadInputTokens();
    usageRecorder.record(SchoolUsage.Kind.CHAT, properties.chatModel(),
        usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
        usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens(),
        cached == null ? 0 : cached,
        sessionId, clientAddress);
  }

}
