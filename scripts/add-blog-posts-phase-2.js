// Migration script: Add Phase 2 blog posts and tags
// Runs via mongosh inside the MongoDB container
// Idempotent — checks for existing documents before inserting

const db = db.getSiblingDB('simonrowe');

// ============================================================================
// Helper functions
// ============================================================================

function findOrCreateTag(name) {
  let tag = db.tags.findOne({ name: { $regex: new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i') } });
  if (tag) {
    print('  Tag already exists: ' + name + ' (' + tag._id + ')');
    return tag._id;
  }
  const now = new Date();
  const result = db.tags.insertOne({
    name: name,
    createdAt: now,
    updatedAt: now,
    _class: 'com.simonrowe.admin.Tag'
  });
  print('  Created tag: ' + name + ' (' + result.insertedId + ')');
  return result.insertedId;
}

function findSkillId(name) {
  const skill = db.skills.findOne({ name: { $regex: new RegExp('^' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$', 'i') } });
  if (skill) {
    return skill._id;
  }
  print('  WARNING: Skill not found: ' + name + ' — skipping');
  return null;
}

function buildDbRefArray(collection, ids) {
  return ids.filter(id => id !== null).map(id => ({
    '$ref': collection,
    '$id': id
  }));
}

// ============================================================================
// Idempotency check
// ============================================================================

const existingPost = db.blogs.findOne({ title: 'Building a CMS from Scratch: Auth0, MDXEditor, and a Media Library' });
if (existingPost) {
  print('--- Phase 2 blog posts already exist (found CMS post). Skipping. ---');
  quit();
}

print('=== Adding Phase 2 blog posts ===');
print('');

// ============================================================================
// Phase 1: Create new tags
// ============================================================================

print('--- Creating new tags ---');

const tagIds = {};
const newTags = ['Auth0', 'Content Management', 'Spring AI', 'MCP (Model Context Protocol)', 'Chatbot', 'Nginx', 'Grafana', 'Observability', 'DevOps'];

newTags.forEach(name => {
  tagIds[name] = findOrCreateTag(name);
});

// Also look up existing tags we need
const existingTags = ['React', 'Spring Boot', 'AI', 'Docker', 'Elasticsearch'];
existingTags.forEach(name => {
  tagIds[name] = findOrCreateTag(name);
});

print('');

// ============================================================================
// Phase 2: Look up existing skills
// ============================================================================

print('--- Looking up skills ---');

const skillIds = {};
const skillNames = ['Java', 'Spring Boot', 'React', 'TypeScript', 'MongoDB', 'Docker', 'Nginx', 'Grafana', 'Kafka', 'Elasticsearch'];

skillNames.forEach(name => {
  skillIds[name] = findSkillId(name);
});

print('');

// ============================================================================
// Phase 3: Blog post content
// ============================================================================

// ---------------------------------------------------------------------------
// Post 6: Building a CMS from Scratch
// ---------------------------------------------------------------------------

const BLOG_6_CONTENT = `In the [previous series](/blogs), I documented rebuilding my portfolio site from scratch using AI coding agents. The site had a blog, skills section, employment history, search, and a contact form — but all content was seeded from migration scripts. It was time to build a proper content management system.

## Why Build a Custom CMS?

The original site ran on Strapi, a headless CMS. It worked, but it was a separate application with its own database, its own deployment, and its own set of problems. When I rebuilt the site with Spring Boot and React, I had a choice: integrate another off-the-shelf CMS, or build one myself.

I chose to build my own for a few reasons:

- **Tight integration**: The CMS could use the same MongoDB database, the same Spring Boot backend, and the same React frontend
- **Exactly what I need**: No unused features, no configuration bloat — just a blog editor, media library, and content management for skills and employment
- **Learning opportunity**: Building a CMS touches authentication, rich text editing, image processing, and CRUD patterns

## Auth0 as the Authentication Gateway

The admin panel needs to be locked down. Rather than building user management from scratch, I used Auth0 as an OAuth2 provider. The Spring Security configuration is minimal:

\`\`\`java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(final HttpSecurity http) throws Exception {
    http
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/admin/**").authenticated()
            .anyRequest().permitAll()
        )
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }
}
\`\`\`

This does three things: protects all \`/api/admin/**\` endpoints with JWT authentication, leaves everything else public, and delegates JWT validation to Auth0's issuer. The frontend obtains tokens via Auth0's React SDK and attaches them as Bearer tokens on admin API calls.

## The Blog Editor with MDXEditor

The centrepiece of the CMS is the blog editor. I needed a rich markdown editor that supports headings, code blocks, images, links, and lists — all within a React component. [MDXEditor](https://mdxeditor.dev/) was the perfect fit.

Here's a simplified version of the editor setup:

\`\`\`tsx
<MDXEditor
  markdown={form.content}
  onChange={(md) => setForm({ ...form, content: md })}
  plugins={[
    headingsPlugin(),
    listsPlugin(),
    quotePlugin(),
    thematicBreakPlugin(),
    linkPlugin(),
    linkDialogPlugin(),
    imagePlugin({ imageUploadHandler: handleImageUpload }),
    codeBlockPlugin({ defaultCodeBlockLanguage: 'java' }),
    codeMirrorPlugin({
      codeBlockLanguages: {
        java: 'Java', typescript: 'TypeScript', javascript: 'JavaScript',
        python: 'Python', yaml: 'YAML', bash: 'Bash', json: 'JSON',
        html: 'HTML', css: 'CSS', sql: 'SQL'
      }
    }),
    toolbarPlugin({
      toolbarContents: () => (
        <>
          <UndoRedo />
          <BoldItalicUnderlineToggles />
          <BlockTypeSelect />
          <CreateLink />
          <InsertImage />
          <InsertCodeBlock />
          <ListsToggle />
        </>
      )
    })
  ]}
/>
\`\`\`

The \`imageUploadHandler\` is the interesting part — when you drag an image into the editor, it uploads to the media library API and returns the URL for embedding.

## The Media Library

Every CMS needs a way to manage images. I built a media library that handles upload, automatic resizing, and variant generation. When an image is uploaded, the backend creates four variants:

\`\`\`java
public List<ImageVariant> generateVariants(Path originalPath, String assetId)
    throws IOException {
  List<ImageVariant> variants = new ArrayList<>();
  Map<String, int[]> sizes = Map.of(
      "thumbnail", new int[]{150, 150},
      "small", new int[]{300, 300},
      "medium", new int[]{600, 600},
      "large", new int[]{1200, 1200}
  );

  for (var entry : sizes.entrySet()) {
    String variantName = entry.getKey();
    int[] dimensions = entry.getValue();
    Path variantPath = generateVariant(originalPath, assetId, variantName,
        dimensions[0], dimensions[1]);
    variants.add(new ImageVariant(variantName, variantPath, dimensions[0], dimensions[1]));
  }
  return variants;
}
\`\`\`

The media library renders as a right-side sliding drawer in the admin UI, using a CSS drawer pattern rather than a modal dialog. This keeps the editing context visible while browsing images.

## Admin API: The DTO Pattern

Blog entities use MongoDB \`@DBRef\` for tags and skills — storing references to other collections rather than embedding the full objects. The admin API uses a DTO pattern to bridge the gap: the frontend sends tag and skill IDs as strings, and the backend resolves them to full \`@DBRef\` entities:

\`\`\`java
@PostMapping("/api/admin/blogs")
public ResponseEntity<AdminBlogResponse> create(@RequestBody BlogRequest request) {
  List<Tag> resolvedTags = request.tags().stream()
      .map(id -> tagRepository.findById(id)
          .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Tag not found: " + id)))
      .toList();

  List<Skill> resolvedSkills = request.skills().stream()
      .map(id -> skillRepository.findById(id)
          .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Skill not found: " + id)))
      .toList();

  Blog blog = new Blog(null, request.title(), request.shortDescription(),
      request.content(), request.published(), request.featuredImageUrl(),
      resolvedTags, resolvedSkills, Instant.now(), Instant.now(), null);

  Blog saved = blogRepository.save(blog);
  return ResponseEntity.status(CREATED).body(toResponse(saved));
}
\`\`\`

## Managing Everything from One Place

Beyond blogs, the CMS manages employment history, skills and skill groups, and the site profile. Each entity follows the same pattern: a list page with Lucide React icons for actions, an editor form, and a detail view. The two-column layout keeps things organised — metadata on the left, content on the right.

## Lessons Learned

Building a custom CMS was the right call for this project. The tight integration with the existing backend means one deployment, one database, and one set of APIs. Auth0 handles the hard parts of authentication. MDXEditor provides a polished editing experience. And the media library gives me full control over image management.

The main lesson? **Don't build what you don't need.** A custom CMS makes sense when you have specific requirements and an existing backend to integrate with. If you're starting from zero, something like Strapi or Sanity might save you weeks. But when you already have the foundation, building on top of it is faster than bolting on a separate system.`;

// ---------------------------------------------------------------------------
// Post 7: Adding AI Chat to My Portfolio
// ---------------------------------------------------------------------------

const BLOG_7_CONTENT = `What if visitors to your portfolio site could just *ask* questions instead of clicking through pages? That's the idea behind the AI chat feature I added to simonrowe.dev — a conversational interface that knows about my skills, experience, and blog posts, and can answer questions as if I'm chatting in real time.

## The Architecture

The chat system has three layers:

1. **Frontend**: A React chat panel that connects via WebSocket (STOMP protocol)
2. **Backend**: A Spring Boot service using Spring AI with a configurable LLM provider
3. **MCP Tools**: Five callable tools that give the AI access to my portfolio data

The key insight is that the LLM doesn't need to *know* everything about me — it just needs the right tools to look things up on demand.

## Spring AI Configuration

Spring AI abstracts away the LLM provider details. Here's the core chat client configuration:

\`\`\`java
@Configuration
public class ChatConfig {

  @Value("\${chat.system-prompt:You are a helpful assistant.}")
  private String systemPrompt;

  @Bean
  public ChatMemory chatMemory() {
    return new ToolFilteringChatMemory(
        MessageWindowChatMemory.builder()
            .maxMessages(20)
            .build());
  }

  @Bean
  public ChatClient chatClient(final ChatClient.Builder builder,
      final ChatMemory chatMemory, final ProfileMcpTools profileMcpTools) {
    return builder
        .defaultSystem(systemPrompt)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .defaultTools(profileMcpTools)
        .build();
  }
}
\`\`\`

The \`ChatClient\` is configured with a system prompt that tells the AI to respond as me, a chat memory advisor for conversation context, and the MCP tools for data access. The \`ToolFilteringChatMemory\` wrapper filters out tool call/response messages before storing them — some LLM providers can't replay those messages in conversation history.

## MCP Tools: Giving the AI Context

The [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) defines how AI models can call external tools. Spring AI makes this simple with the \`@Tool\` annotation. Here are the five tools the AI can use:

\`\`\`java
@Component
public class ProfileMcpTools {

  private final ProfileService profileService;
  private final SearchService searchService;
  private final JobService jobService;
  private final SkillGroupService skillGroupService;

  @Tool(description = "Get Simon's profile — returns his full name, "
      + "professional title, headline summary, detailed bio/description, "
      + "location, email addresses, phone number, and social media links.")
  public ProfileResponse getProfile() {
    return profileService.getProfile();
  }

  @Tool(description = "Search Simon's published blog posts by keyword.")
  public GroupedSearchResponse searchBlogs(@ToolParam(description =
      "Search keywords to match against blog titles and content") String query) {
    return searchService.siteSearch(query);
  }

  @Tool(description = "Get Simon's complete employment history.")
  public List<JobSummaryDto> getJobs() {
    return jobService.getAllJobs();
  }

  @Tool(description = "Get all of Simon's technical skill groups "
      + "with individual skills and proficiency ratings (0-10).")
  public List<SkillGroupSummaryDto> getSkills() {
    return skillGroupService.getAllSkillGroups();
  }

  @Tool(description = "Search across all site content — blogs, jobs, and skills.")
  public GroupedSearchResponse searchSite(@ToolParam(description =
      "Search keywords to match across all site content") String query) {
    return searchService.siteSearch(query);
  }
}
\`\`\`

When a visitor asks "What technologies does Simon work with?", the AI calls \`getSkills()\` and formats the response conversationally. When they ask "Has Simon written about Docker?", it calls \`searchBlogs("Docker")\` and summarises the results.

## Real-Time Streaming with WebSocket/STOMP

Nobody wants to wait for a full AI response to render. Streaming makes the experience feel responsive. The backend uses Spring WebSocket with STOMP to push response chunks as they arrive:

\`\`\`java
@Controller
public class ChatController {

  @MessageMapping("chat.send")
  public void handleChatMessage(final ChatRequest request) {
    String sessionId = request.sessionId();
    String destination = "/topic/chat." + sessionId;

    messagingTemplate.convertAndSend(destination, ChatResponse.streamStart(sessionId));

    StringBuilder fullResponse = new StringBuilder();

    chatService.processMessage(sessionId, request.message())
        .doOnNext(aiResponse -> {
          if (aiResponse.hasToolCalls()) {
            // Reset stream when tool calls are detected
            messagingTemplate.convertAndSend(destination,
                ChatResponse.streamReset(sessionId));
            return;
          }
          String text = aiResponse.getResult().getOutput().getText();
          if (text != null && !text.isEmpty()) {
            fullResponse.append(text);
            messagingTemplate.convertAndSend(destination,
                ChatResponse.streamChunk(sessionId, text));
          }
        })
        .doOnComplete(() -> messagingTemplate.convertAndSend(destination,
            ChatResponse.streamEnd(sessionId, fullResponse.toString())))
        .doOnError(error -> messagingTemplate.convertAndSend(destination,
            ChatResponse.error(sessionId, "Sorry, I'm having trouble right now.")))
        .subscribe();
  }
}
\`\`\`

The interesting bit is the \`streamReset\` — when the AI decides to call a tool, the initial stream might contain partial text that becomes irrelevant. The reset tells the frontend to clear and start fresh with the post-tool-call response.

On the frontend, the STOMP client connects and subscribes to the session-specific topic:

\`\`\`typescript
export function connect(
  sessionId: string,
  onMessage: (response: ChatResponse) => void,
  onConnect?: () => void
): void {
  stompClient = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 5000,
    onConnect: () => {
      stompClient?.subscribe(\`/topic/chat.\${sessionId}\`, (message: IMessage) => {
        const response: ChatResponse = JSON.parse(message.body);
        onMessage(response);
      });
      onConnect?.();
    },
  });
  stompClient.activate();
}
\`\`\`

## Rate Limiting with Bucket4j

An AI chatbot on a public website is an abuse vector. Bucket4j provides token-bucket rate limiting with minimal configuration:

\`\`\`java
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitConfig(
    BucketConfig chat,
    BucketConfig mcp
) {
  public record BucketConfig(int requestsPerMinute) { }
}
\`\`\`

\`\`\`yaml
rate-limit:
  chat:
    requests-per-minute: 20
  mcp:
    requests-per-minute: 60
\`\`\`

The \`RateLimitInterceptor\` creates per-IP buckets and returns 429 with retry headers when limits are exceeded. Two tiers: 20 requests/minute for chat, 60 for MCP tool calls.

## Session Management

Chat sessions are stored in-memory with \`ConcurrentHashMap\` — no database persistence needed for ephemeral conversations. A scheduled cleanup service evicts sessions inactive for 30+ minutes, and each session has a 10-message limit to prevent runaway conversations.

## Lessons Learned

The biggest surprise was how well Spring AI abstracts the LLM integration. Switching from one provider to another required changing a few lines of configuration — the tool definitions, streaming logic, and chat memory all stayed the same.

The MCP tools pattern is powerful. Rather than stuffing my entire CV into the system prompt, the AI retrieves exactly what it needs, when it needs it. This keeps token usage low and responses grounded in actual data.

If you're thinking about adding AI to your own project, start with the tools. Define what data the AI should access, expose it through simple methods, and let the model figure out when to use them. The conversation quality comes from the tools, not from prompt engineering.`;

// ---------------------------------------------------------------------------
// Post 8: Production-Ready
// ---------------------------------------------------------------------------

const BLOG_8_CONTENT = `Building a portfolio site is one thing. Running it in production — with backups, reverse proxy, domain routing, and observability — is another. This post covers the final push to get simonrowe.dev production-ready: admin data operations for backup and restore, Docker Compose for the full stack, nginx for reverse proxy, Pinggy for public exposure, and Grafana Cloud for observability.

## Data Operations: Backup and Restore

Before deploying anything to production, I needed a safety net. The admin panel gained four data operations: backup to Google Drive, restore from Google Drive, clear all data, and rebuild the search index.

The backup service exports all MongoDB collections as JSON, bundles them with the media files from the uploads directory, creates a zip archive with a manifest, and uploads it to Google Drive:

\`\`\`java
public void performBackup() {
  progressTracker.update("Exporting collections...", 10);
  Map<String, List<Document>> collections = exportCollections();

  progressTracker.update("Bundling media files...", 40);
  Path archive = createArchive(collections, uploadsPath);

  progressTracker.update("Uploading to Google Drive...", 70);
  googleDriveService.uploadFile(archive, "backup-" + timestamp() + ".zip", folderId);

  progressTracker.update("Complete", 100);
}
\`\`\`

The restore service is the mirror image — it downloads the archive, validates its structure, creates a safety backup first (in case the restore goes wrong), then restores collections in dependency order: independent collections first (tags, skills, profiles), then dependent ones (blogs with their \`@DBRef\` references).

## Docker Compose: The Full Stack

The entire site runs from a single \`docker-compose.prod.yml\`. Here's the service topology:

\`\`\`yaml
services:
  mongodb:
    image: mongo:8
    volumes:
      - mongodb-data:/data/db
    healthcheck:
      test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]

  kafka:
    image: confluentinc/cp-kafka:7.8.0
    volumes:
      - kafka-data:/var/lib/kafka/data

  elasticsearch:
    image: elasticsearch:8.17.0
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data

  backend:
    image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-backend:latest
    environment:
      SPRING_DATA_MONGODB_URI: mongodb://mongodb:27017/simonrowe
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
      SPRING_ELASTICSEARCH_URIS: http://elasticsearch:9200
      OTEL_SERVICE_NAME: simonrowe-backend
      OTEL_EXPORTER_OTLP_ENDPOINT: http://alloy:4317
    volumes:
      - backend-uploads:/workspace/uploads
    depends_on:
      mongodb: { condition: service_healthy }
      kafka: { condition: service_healthy }
      elasticsearch: { condition: service_healthy }

  frontend:
    image: ghcr.io/simonjamesrowe/simonrowe-dev-monorepo-frontend:latest
    depends_on:
      backend: { condition: service_healthy }

  nginx:
    image: nginx:alpine
    volumes:
      - ./config/nginx/nginx-proxy.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on:
      frontend: { condition: service_healthy }
      backend: { condition: service_healthy }

  pinggy:
    image: pinggy/pinggy
    command: ["--token", "\${PINGGY_TOKEN}", "-l", "nginx:80"]
    depends_on:
      nginx: { condition: service_healthy }

  alloy:
    image: grafana/alloy:latest
    volumes:
      - ./config/alloy/config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro

volumes:
  mongodb-data:
  kafka-data:
  elasticsearch-data:
  backend-uploads:
\`\`\`

Every service has a health check and explicit dependency ordering. The backend won't start until MongoDB, Kafka, and Elasticsearch are healthy. The frontend waits for the backend. Nginx waits for both. Pinggy waits for nginx. This prevents startup race conditions.

Named volumes persist data across restarts — \`mongodb-data\`, \`kafka-data\`, \`elasticsearch-data\`, and \`backend-uploads\` survive container recreation.

## Nginx Reverse Proxy: Domain-Based Routing

With multiple services behind a single entry point, nginx handles routing based on hostname:

\`\`\`nginx
server {
    listen 80;
    server_name simonrowe.dev www.simonrowe.dev;

    location / {
        proxy_pass http://frontend:80;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name api.simonrowe.dev;

    location / {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        # WebSocket upgrade support (for /ws/chat STOMP endpoint)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
\`\`\`

Requests to \`simonrowe.dev\` go to the frontend container. Requests to \`api.simonrowe.dev\` go to the backend. The WebSocket upgrade headers are critical for the chat feature — without them, STOMP connections would fail.

The frontend container itself has its own nginx config that proxies \`/api/\` and \`/uploads/\` to the backend, and serves \`/index.html\` as a fallback for client-side routes.

## Going Public with Pinggy

[Pinggy](https://pinggy.io/) provides a tunnel from a local Docker environment to the public internet. With a Pro subscription, you get a wildcard custom domain. The Pinggy container connects to the nginx container and exposes it at \`*.simonrowe.dev\`.

The beauty of this setup is that there's no cloud infrastructure to manage. The entire stack runs on a single machine (in my case, a Mac Mini), and Pinggy handles the public exposure. DNS records point to Pinggy's servers, and the tunnel routes traffic back to the local Docker network.

## Observability with Grafana Cloud

Running blind in production is a recipe for debugging sessions at midnight. Grafana Alloy collects both logs and traces and ships them to Grafana Cloud:

\`\`\`hcl
// OTLP Trace Receiver — backend sends traces here
otelcol.receiver.otlp "default" {
  grpc { endpoint = "0.0.0.0:4317" }
  output { traces = [otelcol.processor.batch.default.input] }
}

// Batch and export to Grafana Cloud Tempo
otelcol.processor.batch "default" {
  output { traces = [otelcol.exporter.otlphttp.tempo.input] }
}

otelcol.exporter.otlphttp "tempo" {
  client {
    endpoint = sys.env("GRAFANA_CLOUD_TEMPO_ENDPOINT")
    auth     = otelcol.auth.basic.grafana_cloud.handler
  }
}

// Docker log collection — auto-discovers containers
loki.source.docker "default" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.docker.containers.targets
  forward_to = [loki.write.grafana_cloud.receiver]
}

// Ship logs to Grafana Cloud Loki
loki.write "grafana_cloud" {
  endpoint {
    url = sys.env("GRAFANA_CLOUD_LOKI_ENDPOINT")
    basic_auth {
      username = sys.env("GRAFANA_CLOUD_LOKI_USER")
      password = sys.env("GRAFANA_CLOUD_API_KEY")
    }
  }
}
\`\`\`

The backend sends OpenTelemetry traces to Alloy via gRPC. Alloy batches them and forwards to Grafana Cloud Tempo. For logs, Alloy discovers Docker containers via the Docker socket and ships their stdout/stderr to Grafana Cloud Loki. This gives me distributed tracing and centralised logging without installing agents on the host.

## The Frontend Dockerfile

The frontend uses a multi-stage Docker build — Node for building, nginx for serving:

\`\`\`dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY frontend/ .

ARG VITE_RECAPTCHA_SITE_KEY
ARG VITE_API_BASE_URL
ARG VITE_GA_MEASUREMENT_ID

RUN rm -f .env .env.local .env.development
RUN npm run build

FROM nginx:alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
\`\`\`

The \`VITE_*\` environment variables are baked into the bundle at build time via Docker build args. The \`.env\` files are explicitly removed before the build so the args take precedence over local dev defaults.

## What "Production-Ready" Really Means

For a personal project, production-ready doesn't mean five nines of uptime. It means:

- **Data is safe**: Automated backups to Google Drive with one-click restore
- **Deployment is reproducible**: One \`docker compose up -d\` command brings up everything
- **Routing works**: Domain-based routing with SSL termination (via Pinggy)
- **You can debug**: Centralised logs and distributed traces in Grafana Cloud
- **Stateful data persists**: Named volumes survive container restarts

The total infrastructure cost? A Pinggy Pro subscription and Grafana Cloud's free tier. The entire stack runs on a Mac Mini in my home office. It's not how you'd run a SaaS platform, but it's more than enough for a portfolio site — and it's a great learning exercise in production operations.`;

// ============================================================================
// Phase 4: Insert blog posts
// ============================================================================

print('--- Inserting blog posts ---');
print('');

// Post 6: CMS
const post6Tags = buildDbRefArray('tags', [
  tagIds['Auth0'], tagIds['Content Management'], tagIds['React'],
  tagIds['Spring Boot'], tagIds['AI']
]);
const post6Skills = buildDbRefArray('skills', [
  skillIds['Java'], skillIds['Spring Boot'], skillIds['React'],
  skillIds['TypeScript'], skillIds['MongoDB']
]);

const post6Result = db.blogs.insertOne({
  title: 'Building a CMS from Scratch: Auth0, MDXEditor, and a Media Library',
  shortDescription: 'How I replaced Strapi with a custom content management system using Auth0 for authentication, MDXEditor for rich markdown editing, and a media library with automatic image variants.',
  content: BLOG_6_CONTENT,
  published: true,
  featuredImageUrl: '/uploads/blog-phase2-6-cms.jpg',
  tags: post6Tags,
  skills: post6Skills,
  createdDate: new Date('2026-03-20T10:00:00Z'),
  updatedDate: new Date('2026-03-20T10:00:00Z'),
  _class: 'com.simonrowe.admin.Blog'
});
print('Inserted Post 6: CMS (' + post6Result.insertedId + ')');

// Post 7: AI Chat
const post7Tags = buildDbRefArray('tags', [
  tagIds['Spring AI'], tagIds['MCP (Model Context Protocol)'],
  tagIds['AI'], tagIds['Chatbot']
]);
const post7Skills = buildDbRefArray('skills', [
  skillIds['Java'], skillIds['Spring Boot'],
  skillIds['React'], skillIds['TypeScript']
]);

const post7Result = db.blogs.insertOne({
  title: 'Adding AI Chat to My Portfolio: Spring AI, Gemini, and MCP Tools',
  shortDescription: 'I embedded an AI chatbot into my portfolio site using Spring AI and Google Gemini, with MCP tool endpoints that let the AI query my profile, blogs, and skills in real time.',
  content: BLOG_7_CONTENT,
  published: true,
  featuredImageUrl: '/uploads/blog-phase2-7-ai-chat.jpg',
  tags: post7Tags,
  skills: post7Skills,
  createdDate: new Date('2026-03-27T10:00:00Z'),
  updatedDate: new Date('2026-03-27T10:00:00Z'),
  _class: 'com.simonrowe.admin.Blog'
});
print('Inserted Post 7: AI Chat (' + post7Result.insertedId + ')');

// Post 8: Production
const post8Tags = buildDbRefArray('tags', [
  tagIds['Docker'], tagIds['Nginx'], tagIds['Grafana'],
  tagIds['Observability'], tagIds['DevOps']
]);
const post8Skills = buildDbRefArray('skills', [
  skillIds['Docker'], skillIds['MongoDB'],
  skillIds['Kafka'], skillIds['Elasticsearch']
]);

const post8Result = db.blogs.insertOne({
  title: 'Production-Ready: Docker Compose, Backups, and Observability',
  shortDescription: 'Taking a personal project to production with Docker Compose, nginx reverse proxy, Google Drive backups, Pinggy tunnelling, and Grafana Cloud observability.',
  content: BLOG_8_CONTENT,
  published: true,
  featuredImageUrl: '/uploads/blog-phase2-8-production.jpg',
  tags: post8Tags,
  skills: post8Skills,
  createdDate: new Date('2026-04-05T10:00:00Z'),
  updatedDate: new Date('2026-04-05T10:00:00Z'),
  _class: 'com.simonrowe.admin.Blog'
});
print('Inserted Post 8: Production (' + post8Result.insertedId + ')');

print('');
print('=== Phase 2 blog posts seeding complete ===');
print('Total: 3 blog posts, ' + newTags.length + ' new tags');
