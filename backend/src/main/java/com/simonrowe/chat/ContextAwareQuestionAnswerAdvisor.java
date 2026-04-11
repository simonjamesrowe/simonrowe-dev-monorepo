package com.simonrowe.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * A conversation-aware replacement for {@code QuestionAnswerAdvisor} that enriches vector
 * similarity searches with recent user message history.
 *
 * <p>Standard {@code QuestionAnswerAdvisor} searches the vector store using only the current user
 * message. This advisor also incorporates the last {@code historySize} user messages from the
 * active conversation so that follow-up questions ("tell me more", "why?", etc.) retrieve
 * contextually relevant documents rather than returning poor matches against a terse fragment.
 *
 * <p>The enriched query is used exclusively for the vector search. The original user message is
 * preserved in the rendered prompt template so the model sees the real question.
 */
public class ContextAwareQuestionAnswerAdvisor implements BaseAdvisor {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContextAwareQuestionAnswerAdvisor.class);

  private static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";
  private static final int DEFAULT_ORDER = 100;
  private static final int DEFAULT_HISTORY_SIZE = 5;

  private static final String PROMPT_TEMPLATE =
      "{query}"
          + System.lineSeparator()
          + System.lineSeparator()
          + "Context information is below, surrounded by ---------------------"
          + System.lineSeparator()
          + System.lineSeparator()
          + "---------------------"
          + System.lineSeparator()
          + "{question_answer_context}"
          + System.lineSeparator()
          + "---------------------"
          + System.lineSeparator()
          + System.lineSeparator()
          + "Given the context and provided history information and not prior knowledge,"
          + System.lineSeparator()
          + "reply to the user comment. If the answer is not in the context, inform"
          + System.lineSeparator()
          + "the user that you can't answer the question.";

  private final VectorStore vectorStore;
  private final ChatMemory chatMemory;
  private final SearchRequest searchRequest;
  private final int order;
  private final int historySize;

  private ContextAwareQuestionAnswerAdvisor(final Builder builder) {
    this.vectorStore = builder.vectorStore;
    this.chatMemory = builder.chatMemory;
    this.searchRequest = builder.searchRequest;
    this.order = builder.order;
    this.historySize = builder.historySize;
  }

  /**
   * Creates a new {@link Builder} for this advisor.
   *
   * @param vectorStore the vector store to search
   * @param chatMemory the chat memory holding conversation history
   * @return a new builder instance
   */
  public static Builder builder(final VectorStore vectorStore, final ChatMemory chatMemory) {
    return new Builder(vectorStore, chatMemory);
  }

  @Override
  public ChatClientRequest before(
      final ChatClientRequest request, final AdvisorChain chain) {
    String currentMessage = request.prompt().getUserMessage().getText();
    String enrichedQuery = buildEnrichedQuery(request.context(), currentMessage);

    LOG.debug("Enriched vector search query: {}", enrichedQuery);

    SearchRequest enrichedSearchRequest =
        SearchRequest.builder()
            .query(enrichedQuery)
            .similarityThreshold(searchRequest.getSimilarityThreshold())
            .topK(searchRequest.getTopK())
            .build();

    List<Document> documents = vectorStore.similaritySearch(enrichedSearchRequest);
    String contextText = documents.stream()
        .map(Document::getText)
        .collect(Collectors.joining(System.lineSeparator()));

    String renderedPrompt = PROMPT_TEMPLATE
        .replace("{query}", currentMessage)
        .replace("{question_answer_context}", contextText);

    Map<String, Object> context = request.context();
    context.put(RETRIEVED_DOCUMENTS, documents);

    return request.mutate()
        .prompt(request.prompt().augmentUserMessage(renderedPrompt))
        .build();
  }

  @Override
  public ChatClientResponse after(
      final ChatClientResponse response, final AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return order;
  }

  @Override
  public String getName() {
    return ContextAwareQuestionAnswerAdvisor.class.getSimpleName();
  }

  private String buildEnrichedQuery(
      final Map<String, Object> context, final String currentMessage) {
    Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
    if (conversationId == null) {
      return currentMessage;
    }

    List<Message> history = chatMemory.get(conversationId.toString());
    if (history == null || history.isEmpty()) {
      return currentMessage;
    }

    List<String> recentUserMessages = history.stream()
        .filter(message -> message instanceof UserMessage)
        .map(Message::getText)
        .collect(Collectors.toCollection(ArrayList::new));

    int fromIndex = Math.max(0, recentUserMessages.size() - historySize);
    List<String> selectedMessages =
        recentUserMessages.subList(fromIndex, recentUserMessages.size());

    if (selectedMessages.isEmpty()) {
      return currentMessage;
    }

    return String.join(" ", selectedMessages) + " " + currentMessage;
  }

  /** Builder for {@link ContextAwareQuestionAnswerAdvisor}. */
  public static final class Builder {

    private final VectorStore vectorStore;
    private final ChatMemory chatMemory;
    private SearchRequest searchRequest = SearchRequest.builder().build();
    private int order = DEFAULT_ORDER;
    private int historySize = DEFAULT_HISTORY_SIZE;

    private Builder(final VectorStore vectorStore, final ChatMemory chatMemory) {
      this.vectorStore = vectorStore;
      this.chatMemory = chatMemory;
    }

    /**
     * Sets the base {@link SearchRequest} whose threshold and topK are applied to all searches.
     *
     * @param searchRequest the search configuration
     * @return this builder
     */
    public Builder searchRequest(final SearchRequest searchRequest) {
      this.searchRequest = searchRequest;
      return this;
    }

    /**
     * Sets the advisor execution order.
     *
     * @param order the order value (lower runs earlier)
     * @return this builder
     */
    public Builder order(final int order) {
      this.order = order;
      return this;
    }

    /**
     * Sets the maximum number of prior user messages to include in the enriched search query.
     *
     * @param historySize the number of prior user messages (default 5)
     * @return this builder
     */
    public Builder historySize(final int historySize) {
      this.historySize = historySize;
      return this;
    }

    /**
     * Builds the advisor.
     *
     * @return a configured {@link ContextAwareQuestionAnswerAdvisor}
     */
    public ContextAwareQuestionAnswerAdvisor build() {
      return new ContextAwareQuestionAnswerAdvisor(this);
    }
  }
}
