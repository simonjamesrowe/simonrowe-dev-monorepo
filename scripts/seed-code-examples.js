// Switch to the simonrowe database
use('simonrowe');

function skillRef(id) {
  return { '$ref': 'skills', '$id': ObjectId(id) };
}

// Drop existing code_examples collection
db.code_examples.drop();

db.code_examples.insertMany([
  {
    title: 'Spring AI Vector Embeddings for Semantic Search',
    description: 'Demonstrates how to use Spring AI\'s VectorStore to create and manage vector embeddings for semantic search. The service converts blogs, jobs, skills, and code examples into vector documents with rich metadata, enabling AI-powered similarity search across all content types.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('5f635b8b5ee4c9001d2b9634'),
      skillRef('5f635d905ee4c9001d2b9651')
    ],
    code: '```java\n@Service\npublic class EmbeddingService {\n  private final VectorStore vectorStore;\n  private final TokenTextSplitter splitter;\n\n  @WithSpan\n  public void embedBlog(final Blog blog) {\n    removeContent(blog.id());\n    if (!blog.published()) {\n      return;\n    }\n    Map<String, Object> metadata = new HashMap<>();\n    metadata.put("sourceId", blog.id());\n    metadata.put("sourceType", "blog");\n    metadata.put("title", blog.title());\n    if (blog.tags() != null) {\n      metadata.put("tags", blog.tags().stream()\n          .map(t -> t.name())\n          .collect(Collectors.joining(",")));\n    }\n    metadata.put("url", "/blogs/" + blog.id());\n    String content = blog.title() + "\\n\\n" \n        + blog.shortDescription() + "\\n\\n" + blog.content();\n    embedContent(content, metadata);\n  }\n\n  private void embedContent(String content, Map<String, Object> metadata) {\n    Document document = new Document(content, metadata);\n    List<Document> chunks = splitter.apply(List.of(document));\n    vectorStore.add(chunks);\n  }\n\n  @Scheduled(cron = "${search.sync.cron:0 0 */4 * * *}")\n  public void fullVectorSync() {\n    embedAllBlogs();\n    embedAllJobs();\n    embedAllSkills();\n    embedAllCodeExamples();\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Kafka Consumer with Resilient Retry & Dead Letter Topics',
    description: 'Event-driven Kafka consumer using Spring\'s `@RetryableTopic` for automatic retry with exponential backoff and Dead Letter Topic (DLT) routing. Handles content change events across multiple entity types using pattern matching switch expressions.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('5f635eae5ee4c9001d2b9667'),
      skillRef('5f635ef75ee4c9001d2b966a')
    ],
    code: '```java\n@Component\npublic class EmbeddingChangeConsumer {\n\n  @RetryableTopic(\n      attempts = "4",\n      backoff = @Backoff(delay = 1000, multiplier = 2),\n      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,\n      dltTopicSuffix = ".DLT"\n  )\n  @KafkaListener(topics = "content-changes", groupId = "embedding-indexer")\n  @WithSpan\n  public void handleContentChange(final ContentChangeEvent event) {\n    if (event.eventType() == EventType.DELETED) {\n      embeddingService.removeContent(event.contentId());\n      return;\n    }\n    switch (event.contentType()) {\n      case BLOG -> handleBlog(event.contentId());\n      case JOB -> handleJob(event.contentId());\n      case SKILL -> handleSkill(event.contentId());\n      default -> LOG.warn("Unknown content type: {}", event.contentType());\n    }\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'PDF CV Generation with OpenPDF Layout Engine',
    description: 'Generates a professional PDF CV/resume using the OpenPDF (iText fork) library. Features a two-column layout with sidebar for contact info and skills, automatic multi-page support with column text flow, and markdown-to-plaintext conversion for rich job descriptions.',
    language: 'java',
    skills: [
      skillRef('699fd8f4f1a4949fbe59166e'),
      skillRef('5f635b6a5ee4c9001d2b9632')
    ],
    code: '```java\n@Component\npublic class ResumePdfRenderer {\n  private static final float SIDEBAR_WIDTH = 175f;\n  private static final float MAIN_X = 186f;\n\n  public byte[] render(ResumeData data) {\n    ByteArrayOutputStream out = new ByteArrayOutputStream();\n    Document document = new Document(PageSize.A4, 0, 0, 0, 0);\n    PdfWriter writer = PdfWriter.getInstance(document, out);\n    document.open();\n\n    drawSidebarBackground(writer.getDirectContentUnder());\n    drawHeadlineBox(writer.getDirectContent(), data);\n    drawSidebar(writer.getDirectContent(), data);\n    drawMainContent(document, writer, data);\n\n    document.close();\n    return out.toByteArray();\n  }\n\n  private void drawMainContent(Document document, PdfWriter writer, ResumeData data) {\n    ColumnText ct = new ColumnText(writer.getDirectContent());\n    ct.setSimpleColumn(llx, PAGE_MARGIN, urx, CONTENT_START_Y);\n    // Employment and education sections...\n    int status = ct.go();\n    while (ColumnText.hasMoreText(status)) {\n      document.newPage();\n      drawSidebarBackground(writer.getDirectContentUnder());\n      ct.setSimpleColumn(llx, PAGE_MARGIN, urx, PAGE_HEIGHT - PAGE_MARGIN);\n      status = ct.go();\n    }\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Google Drive API Integration',
    description: 'Google Drive API integration for cloud backup management. Handles folder creation, file upload with streaming, paginated file listing, and download. Uses OAuth2 credentials with null-safe drive instance injection for environments where Google Drive is not configured.',
    language: 'java',
    skills: [
      skillRef('699fd8f4f1a4949fbe59166e'),
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('69b7b7328fa5685d5f591671')
    ],
    code: '```java\n@Service\npublic class GoogleDriveService {\n  @Nullable\n  private final Drive drive;\n\n  public String findOrCreateFolder() throws IOException {\n    checkDrive();\n    FileList result = drive.files().list()\n        .setQ("name = \'" + FOLDER_NAME + "\' and mimeType = \'"\n            + FOLDER_MIME + "\' and trashed = false")\n        .setFields("files(id, name)")\n        .setSupportsAllDrives(true)\n        .setIncludeItemsFromAllDrives(true)\n        .execute();\n\n    if (result.getFiles() != null && !result.getFiles().isEmpty()) {\n      return result.getFiles().get(0).getId();\n    }\n\n    File folderMetadata = new File();\n    folderMetadata.setName(FOLDER_NAME);\n    folderMetadata.setMimeType(FOLDER_MIME);\n    return drive.files().create(folderMetadata)\n        .setFields("id").setSupportsAllDrives(true).execute().getId();\n  }\n\n  public List<BackupMetadata> listBackups(String folderId) throws IOException {\n    List<BackupMetadata> backups = new ArrayList<>();\n    String pageToken = null;\n    do {\n      FileList result = drive.files().list()\n          .setQ("\'" + folderId + "\' in parents and trashed = false")\n          .setOrderBy("createdTime desc")\n          .setPageSize(100).setPageToken(pageToken)\n          .setSupportsAllDrives(true).execute();\n      // Process results...\n      pageToken = result.getNextPageToken();\n    } while (pageToken != null);\n    return backups;\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Spring Security OAuth2 JWT Configuration',
    description: 'Minimal but complete Spring Security 6 configuration for a stateless REST API with OAuth2/JWT authentication. Protects admin endpoints while keeping public API routes open, with CORS support and disabled CSRF for API usage.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('5f635bb85ee4c9001d2b9635'),
      skillRef('69b7b7328fa5685d5f591671')
    ],
    code: '```java\n@Configuration\n@EnableWebSecurity\npublic class SecurityConfig {\n\n  @Bean\n  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {\n    http\n        .cors(Customizer.withDefaults())\n        .authorizeHttpRequests(auth -> auth\n            .requestMatchers("/api/admin/**").authenticated()\n            .anyRequest().permitAll()\n        )\n        .headers(headers -> headers.cacheControl(cache -> cache.disable()))\n        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))\n        .csrf(csrf -> csrf.disable())\n        .sessionManagement(session ->\n            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)\n        );\n    return http.build();\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Elasticsearch Multi-Field Full-Text Search',
    description: 'Full-text search service using the Elasticsearch Java API Client. Performs multi-field queries across name, description, and company fields with relevance scoring, then groups results by content type (blogs, jobs, skills) for the frontend search UI.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('5f635d7e5ee4c9001d2b964f'),
      skillRef('699fd8f4f1a4949fbe59166e')
    ],
    code: '```java\n@Service\npublic class SearchService {\n  public GroupedSearchResponse siteSearch(String query) {\n    String sanitized = sanitizeQuery(query);\n    if (sanitized.length() < MIN_QUERY_LENGTH) {\n      return new GroupedSearchResponse(List.of(), List.of(), List.of());\n    }\n    SearchResponse<SiteSearchDocument> response = client.search(s -> s\n            .index(ElasticsearchConfig.SITE_SEARCH_INDEX)\n            .size(maxResultsPerGroup * 3)\n            .query(q -> q\n                .multiMatch(mm -> mm\n                    .query(sanitized)\n                    .fields("name", "shortDescription", "longDescription", "company")\n                    .type(TextQueryType.BestFields)))\n            .sort(sort -> sort.score(sc -> sc.order(SortOrder.Desc)))\n            .sort(sort -> sort.field(f -> f\n                .field("sortDate").order(SortOrder.Desc).missing("_last"))),\n        SiteSearchDocument.class);\n\n    Map<String, List<SiteSearchDocument>> grouped = response.hits().hits().stream()\n        .map(Hit::source).filter(Objects::nonNull)\n        .collect(Collectors.groupingBy(SiteSearchDocument::type,\n            LinkedHashMap::new, Collectors.toList()));\n\n    return new GroupedSearchResponse(\n        toSearchResults(grouped.getOrDefault("blog", List.of())),\n        toSearchResults(grouped.getOrDefault("job", List.of())),\n        toSearchResults(grouped.getOrDefault("skill", List.of())));\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Elasticsearch Index Schema & Mapping',
    description: 'Programmatic Elasticsearch index creation with typed property mappings using the Java API Client. Creates indices on application startup with text analyzers, keyword fields, date formatting, and multi-field definitions for search and filtering.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('5f635d7e5ee4c9001d2b964f')
    ],
    code: '```java\n@Configuration\npublic class ElasticsearchConfig {\n\n  @EventListener(ApplicationReadyEvent.class)\n  @Order(1)\n  public void createIndicesOnStartup() {\n    createSiteSearchIndex();\n    createBlogSearchIndex();\n  }\n\n  private void createSiteSearchIndex() {\n    boolean exists = client.indices().exists(e -> e.index(SITE_SEARCH_INDEX)).value();\n    if (exists) return;\n\n    client.indices().create(c -> c\n        .index(SITE_SEARCH_INDEX)\n        .settings(IndexSettings.of(s -> s\n            .numberOfShards("1").numberOfReplicas("0")))\n        .mappings(m -> m\n            .properties("name", Property.of(p -> p\n                .text(TextProperty.of(t -> t\n                    .analyzer("standard")\n                    .fields("keyword", Property.of(kp -> kp\n                        .keyword(KeywordProperty.of(k -> k))))))))\n            .properties("type", Property.of(p -> p\n                .keyword(KeywordProperty.of(k -> k))))\n            .properties("sortDate", Property.of(p -> p\n                .date(d -> d.format("strict_date_optional_time"))))));\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'MongoDB Backup to Google Drive with Progress Streaming',
    description: 'Full database backup service that exports MongoDB collections to JSON, packages uploads and Elasticsearch snapshots into a ZIP archive, and uploads to Google Drive. Streams progress updates to the frontend via Server-Sent Events for real-time status feedback.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('5f635d905ee4c9001d2b9651'),
      skillRef('699fd8f4f1a4949fbe59166e')
    ],
    code: '```java\n@Service\npublic class BackupService {\n  private static final Set<String> BACKUP_COLLECTIONS = Set.of(\n      "blogs", "tags", "skills", "skill_groups", "jobs",\n      "profiles", "social_medias", "tourSteps", "media_assets", "code_examples"\n  );\n\n  public void performBackup() {\n    Path tempFile = Files.createTempFile("backup-", ".zip");\n    try (ZipOutputStream zos = new ZipOutputStream(\n            new BufferedOutputStream(Files.newOutputStream(tempFile)))) {\n\n      for (String collectionName : BACKUP_COLLECTIONS) {\n        operationsService.updateProgress("Exporting: " + collectionName, progress);\n        MongoCollection<RawBsonDocument> collection =\n            mongoClient.getDatabase(databaseName)\n                .getCollection(collectionName, RawBsonDocument.class);\n        List<RawBsonDocument> docs = collection.find().into(new ArrayList<>());\n\n        zos.putNextEntry(new ZipEntry("collections/" + collectionName + ".json"));\n        // Write JSON array of BSON documents\n        zos.closeEntry();\n      }\n\n      operationsService.updateProgress("Uploading to Google Drive...", 80);\n      String folderId = googleDriveService.findOrCreateFolder();\n      googleDriveService.uploadFile(folderId, fileName, \n          Files.newInputStream(tempFile), Files.size(tempFile));\n    }\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'WebSocket Chat with Spring AI Streaming',
    description: 'WebSocket controller using STOMP messaging for real-time AI chat. Processes messages through Spring AI with reactive streaming, handles tool call detection and stream resets, and enforces per-session rate limiting with concurrent counters.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('699fd8f4f1a4949fbe59166e')
    ],
    code: '```java\n@Controller\npublic class ChatController {\n  private static final int MAX_MESSAGES_PER_SESSION = 10;\n  private final ConcurrentHashMap<String, AtomicInteger> sessionMessageCounts =\n      new ConcurrentHashMap<>();\n\n  @MessageMapping("chat.send")\n  public void handleChatMessage(ChatRequest request) {\n    String sessionId = request.sessionId();\n    String destination = "/topic/chat." + sessionId;\n\n    int count = sessionMessageCounts\n        .computeIfAbsent(sessionId, key -> new AtomicInteger(0))\n        .incrementAndGet();\n    if (count > MAX_MESSAGES_PER_SESSION) {\n      messagingTemplate.convertAndSend(destination,\n          ChatResponse.error(sessionId, "Message limit reached"));\n      return;\n    }\n\n    messagingTemplate.convertAndSend(destination, ChatResponse.streamStart(sessionId));\n    StringBuilder fullResponse = new StringBuilder();\n\n    chatService.processMessage(sessionId, request.message())\n        .doOnNext(aiResponse -> {\n          if (aiResponse.hasToolCalls()) { return; }\n          String text = aiResponse.getResult().getOutput().getText();\n          fullResponse.append(text);\n          messagingTemplate.convertAndSend(destination,\n              ChatResponse.streamChunk(sessionId, text));\n        })\n        .doOnComplete(() -> messagingTemplate.convertAndSend(destination,\n            ChatResponse.streamEnd(sessionId, fullResponse.toString())))\n        .doOnError(error -> messagingTemplate.convertAndSend(destination,\n            ChatResponse.error(sessionId, "Error processing chat")))\n        .subscribe();\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Token Bucket Rate Limiting Interceptor',
    description: 'HTTP interceptor implementing token bucket rate limiting with the Bucket4j library. Provides per-client IP rate limiting with separate buckets for chat and MCP endpoints, proper HTTP 429 responses with Retry-After headers, and X-Forwarded-For support for proxy environments.',
    language: 'java',
    skills: [
      skillRef('5f635b6a5ee4c9001d2b9632'),
      skillRef('699fd8f4f1a4949fbe59166e')
    ],
    code: '```java\n@Component\npublic class RateLimitInterceptor implements HandlerInterceptor {\n  private final ConcurrentHashMap<String, Bucket> chatBuckets = new ConcurrentHashMap<>();\n  private final ConcurrentHashMap<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();\n\n  @Override\n  public boolean preHandle(HttpServletRequest request,\n      HttpServletResponse response, Object handler) {\n    String clientIp = getClientIp(request);\n    String path = request.getRequestURI();\n\n    ConcurrentHashMap<String, Bucket> bucketMap = path.startsWith("/mcp")\n        ? mcpBuckets : chatBuckets;\n    int requestsPerMinute = path.startsWith("/mcp")\n        ? config.mcp().requestsPerMinute()\n        : config.chat().requestsPerMinute();\n\n    Bucket bucket = bucketMap.computeIfAbsent(clientIp,\n        key -> createBucket(requestsPerMinute));\n    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);\n    response.setHeader("X-RateLimit-Remaining",\n        String.valueOf(probe.getRemainingTokens()));\n\n    if (probe.isConsumed()) return true;\n\n    long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(\n        probe.getNanosToWaitForRefill()) + 1;\n    response.setHeader("Retry-After", String.valueOf(waitSeconds));\n    response.setStatus(429);\n    response.getWriter().write(\n        "{\\"error\\":\\"Rate limit exceeded\\",\\"retryAfter\\":" + waitSeconds + "}");\n    return false;\n  }\n\n  private Bucket createBucket(int requestsPerMinute) {\n    return Bucket.builder().addLimit(Bandwidth.builder()\n        .capacity(requestsPerMinute)\n        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))\n        .build()).build();\n  }\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'WebSocket Chat Service with STOMP Protocol',
    description: 'TypeScript service for real-time WebSocket communication using the STOMP protocol over WebSockets. Manages connection lifecycle, session-scoped message subscriptions, automatic reconnection, and protocol-aware URL construction for HTTP/HTTPS environments.',
    language: 'typescript',
    skills: [
      skillRef('5f635e935ee4c9001d2b9665'),
      skillRef('5f635e625ee4c9001d2b9660')
    ],
    code: '```typescript\nimport { Client, IMessage } from \'@stomp/stompjs\'\n\nlet stompClient: Client | null = null\nlet activeSessionId: string | null = null\n\nexport function connect(\n  sessionId: string,\n  onMessage: (response: ChatResponse) => void,\n  onConnect?: () => void,\n  onError?: (error: string) => void\n): void {\n  disconnect()\n  activeSessionId = sessionId\n\n  stompClient = new Client({\n    brokerURL: WS_URL,\n    reconnectDelay: 5000,\n    onConnect: () => {\n      if (activeSessionId !== sessionId) return\n      stompClient?.subscribe(\n        `/topic/chat.${sessionId}`, \n        (message: IMessage) => {\n          const response = JSON.parse(message.body) as ChatResponse\n          onMessage(response)\n        })\n      onConnect?.()\n    },\n    onStompError: (frame) => {\n      onError?.(frame.headers[\'message\'] || \'WebSocket connection error\')\n    },\n  })\n  stompClient.activate()\n}\n\nexport function sendMessage(request: ChatRequest): void {\n  if (stompClient?.connected) {\n    stompClient.publish({\n      destination: \'/app/chat.send\',\n      body: JSON.stringify(request),\n    })\n  }\n}\n\nexport function disconnect(): void {\n  activeSessionId = null\n  stompClient?.forceDisconnect()\n  stompClient?.deactivate()\n  stompClient = null\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Tour State Management with useReducer & Context',
    description: 'React Context provider using `useReducer` for complex multi-step tour state management. Coordinates route navigation, responsive breakpoint detection, smooth scrolling to target elements, and async data fetching — all with proper cleanup and memoization.',
    language: 'typescript',
    skills: [
      skillRef('5f635e935ee4c9001d2b9665'),
      skillRef('5f635e625ee4c9001d2b9660')
    ],
    code: '```typescript\ntype TourAction =\n  | { type: \'START\'; steps: TourStep[] }\n  | { type: \'NEXT\' }\n  | { type: \'PREV\' }\n  | { type: \'EXIT\' }\n  | { type: \'SET_SEARCH_VALUE\'; value: string }\n\nfunction tourReducer(state: TourState, action: TourAction): TourState {\n  switch (action.type) {\n    case \'START\':\n      return { ...state, isActive: true, currentStepIndex: 0, steps: action.steps }\n    case \'NEXT\':\n      return state.currentStepIndex >= state.steps.length - 1\n        ? { ...initialState }\n        : { ...state, currentStepIndex: state.currentStepIndex + 1 }\n    case \'PREV\':\n      return state.currentStepIndex <= 0\n        ? state\n        : { ...state, currentStepIndex: state.currentStepIndex - 1 }\n    case \'EXIT\':\n      return { ...initialState }\n    default:\n      return state\n  }\n}\n\nexport function TourProvider({ children }: { children: ReactNode }) {\n  const [state, dispatch] = useReducer(tourReducer, initialState)\n  const navigate = useNavigate()\n\n  const next = useCallback(() => {\n    const nextStep = state.steps[state.currentStepIndex + 1]\n    if (nextStep?.route && nextStep.route !== location.pathname) {\n      navigate(nextStep.route)\n    }\n    dispatch({ type: \'NEXT\' })\n  }, [state.currentStepIndex, state.steps, navigate])\n\n  useEffect(() => {\n    if (!state.isActive) return\n    const el = document.querySelector(\n      state.steps[state.currentStepIndex]?.targetSelector)\n    el?.scrollIntoView({ behavior: \'smooth\', block: \'center\' })\n  }, [state.isActive, state.currentStepIndex])\n\n  return (\n    <TourContext.Provider value={{ ...state, start, next, prev, exit }}>\n      {children}\n    </TourContext.Provider>\n  )\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Streaming Chat Panel with Timeout Management',
    description: 'React chat component handling real-time AI response streaming with sophisticated state management. Uses refs for non-rendering state, implements stream timeout detection, supports cancellation on unmount, and handles edge cases like tool-call resets and empty responses.',
    language: 'typescript',
    skills: [
      skillRef('5f635e935ee4c9001d2b9665'),
      skillRef('5f635e625ee4c9001d2b9660')
    ],
    code: '```typescript\nexport function ChatPanel({ initialQuery, onClose }: ChatPanelProps) {\n  const [messages, setMessages] = useState<Message[]>([])\n  const [streamingContent, setStreamingContent] = useState<string | null>(null)\n  const streamContentRef = useRef(\'\')\n  const streamFinalized = useRef(false)\n  const cancelledRef = useRef(false)\n  const streamTimeoutRef = useRef<ReturnType<typeof setTimeout>>()\n\n  const finalizeStream = useCallback(() => {\n    if (streamFinalized.current) return\n    streamFinalized.current = true\n    clearTimeout(streamTimeoutRef.current)\n    const content = streamContentRef.current\n    setStreamingContent(null)\n    if (content) {\n      setMessages(msgs => [...msgs,\n        { role: \'assistant\', content, timestamp: formatTimestamp() }])\n    }\n  }, [])\n\n  const onMessage = useCallback((response: ChatResponse) => {\n    if (cancelledRef.current) return\n    switch (response.type) {\n      case \'STREAM_START\':\n        streamContentRef.current = \'\'\n        streamFinalized.current = false\n        setStreamingContent(\'\')\n        break\n      case \'STREAM_CHUNK\':\n        streamContentRef.current += response.content\n        setStreamingContent(streamContentRef.current)\n        break\n      case \'STREAM_END\':\n        finalizeStream()\n        break\n      case \'ERROR\':\n        setStreamingContent(null)\n        setMessages(msgs => [...msgs,\n          { role: \'assistant\', content: response.content || \'Error\' }])\n        break\n    }\n  }, [finalizeStream])\n\n  useEffect(() => {\n    return () => {\n      cancelledRef.current = true\n      clearTimeout(streamTimeoutRef.current)\n      chatService.disconnect()\n    }\n  }, [])\n}\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'Production Docker Compose with Health Checks',
    description: 'Production Docker Compose configuration orchestrating 9 services including MongoDB, Kafka (KRaft mode), Elasticsearch, Spring Boot backend, React frontend, nginx reverse proxy, Grafana Alloy for observability, and Portainer for container management. Features health checks, dependency chains, volume initialization, and Docker-in-Docker socket mounting.',
    language: 'yaml',
    skills: [
      skillRef('5f635c745ee4c9001d2b9641'),
      skillRef('5f635ca45ee4c9001d2b9643'),
      skillRef('5f635eae5ee4c9001d2b9667'),
      skillRef('5f635d905ee4c9001d2b9651'),
      skillRef('5f635d7e5ee4c9001d2b964f')
    ],
    code: '```yaml\nservices:\n  mongodb:\n    image: mongo:8\n    restart: unless-stopped\n    volumes:\n      - mongodb-data:/data/db\n    healthcheck:\n      test: ["CMD", "mongosh", "--eval", "db.adminCommand(\'ping\')"]\n      interval: 10s\n      timeout: 5s\n      retries: 5\n\n  kafka:\n    image: confluentinc/cp-kafka:7.8.0\n    restart: unless-stopped\n    environment:\n      KAFKA_NODE_ID: 1\n      KAFKA_PROCESS_ROLES: broker,controller\n      KAFKA_LISTENERS: PLAINTEXT://:29092,CONTROLLER://:9093\n      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093\n      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk\n    healthcheck:\n      test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:29092"]\n\n  elasticsearch:\n    image: elasticsearch:8.17.0\n    environment:\n      discovery.type: single-node\n      xpack.security.enabled: "false"\n      ES_JAVA_OPTS: "-Xms512m -Xmx512m"\n\n  backend:\n    image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest\n    depends_on:\n      mongodb: { condition: service_healthy }\n      kafka: { condition: service_healthy }\n      elasticsearch: { condition: service_healthy }\n    volumes:\n      - /var/run/docker.sock:/var/run/docker.sock\n    healthcheck:\n      test: ["CMD", "bash", "-c", "echo > /dev/tcp/localhost/8081"]\n\n  frontend:\n    image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend:latest\n    depends_on:\n      backend: { condition: service_healthy }\n\n  nginx:\n    image: nginx:alpine\n    depends_on:\n      frontend: { condition: service_healthy }\n      backend: { condition: service_healthy }\n\n  alloy:\n    image: grafana/alloy:latest\n    volumes:\n      - /var/run/docker.sock:/var/run/docker.sock:ro\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  },
  {
    title: 'CI/CD Pipeline with Quality Gates',
    description: 'GitHub Actions CI/CD pipeline with comprehensive quality gates. The CI workflow enforces code quality through Checkstyle (Google Java Style), verifies test coverage with JaCoCo, generates CycloneDX SBOM for dependency tracking, and runs SonarCloud analysis. The publish workflow builds and pushes container images to GitHub Container Registry.',
    language: 'yaml',
    skills: [
      skillRef('5f635c745ee4c9001d2b9641'),
      skillRef('5f635c845ee4c9001d2b9642'),
      skillRef('69b7b7328fa5685d5f59166b'),
      skillRef('5f635c1e5ee4c9001d2b963b')
    ],
    code: '```yaml\nname: CI\non:\n  pull_request:\n    branches: [main]\n\njobs:\n  backend:\n    name: Backend Build & Test\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-java@v4\n        with: { java-version: \'21\', distribution: \'temurin\' }\n      - uses: gradle/actions/setup-gradle@v4\n\n      - name: Run Checkstyle\n        run: ./gradlew :backend:checkstyleMain :backend:checkstyleTest\n      - name: Run tests\n        run: ./gradlew :backend:test\n      - name: Verify coverage\n        run: ./gradlew :backend:jacocoTestCoverageVerification\n      - name: Generate CycloneDX BOM\n        run: ./gradlew cyclonedxBom\n      - name: SonarCloud analysis\n        if: env.SONAR_TOKEN != \'\'\n        run: ./gradlew sonar\n\n  frontend:\n    runs-on: ubuntu-latest\n    defaults:\n      run: { working-directory: frontend }\n    steps:\n      - uses: actions/checkout@v4\n      - uses: actions/setup-node@v4\n        with: { node-version: \'22\', cache: \'npm\' }\n      - run: npm ci\n      - run: npm test\n      - run: npm run build\n```',
    createdAt: new Date(),
    updatedAt: new Date()
  }
]);

print('Inserted ' + db.code_examples.countDocuments() + ' code examples into code_examples collection.');
