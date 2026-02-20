# Research: Content Management System

**Feature**: 007-content-management
**Date**: 2026-02-21
**Status**: Complete

## Research Areas

### 1. Auth0 + Spring Security 6 Integration

**Question**: How to secure admin REST endpoints with Auth0 JWT tokens using Spring Boot 4 / Spring Security 6?

**Findings**:

Spring Security 6 (bundled with Spring Boot 4) provides first-class OAuth2 Resource Server support via `spring-boot-starter-oauth2-resource-server`. This enables stateless JWT validation on every request without maintaining sessions.

**Configuration approach**:

```yaml
# application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://${AUTH0_DOMAIN}/
          audiences: ${AUTH0_AUDIENCE}
```

**Security filter chain**:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            );
        return http.build();
    }
}
```

**Auth0 audience validation**: Spring Security's default JWT decoder validates the `iss` (issuer) claim automatically. Auth0 requires additional `aud` (audience) claim validation. This is achieved by providing a custom `JwtDecoder` bean that adds an `AudienceValidator`:

```java
@Bean
public JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
    OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuerUri);
    OAuth2TokenValidator<Jwt> combined = new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator);
    decoder.setJwtValidator(combined);
    return decoder;
}
```

**Decision**: Use `spring-boot-starter-oauth2-resource-server` with custom `AudienceValidator` for Auth0. No additional Auth0-specific SDK needed on the backend. Role/permission checks can use Auth0 custom claims or simply rely on "authenticated = admin" since Auth0 is configured with no self-registration.

**Testing approach**: Spring Security Test provides `SecurityMockMvcRequestPostProcessors.jwt()` for injecting mock JWT tokens in integration tests, avoiding the need for a real Auth0 instance during testing:

```java
mockMvc.perform(post("/api/admin/blogs")
    .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_admin")))
    .contentType(MediaType.APPLICATION_JSON)
    .content(blogJson))
    .andExpect(status().isCreated());
```

---

### 2. React Markdown Editor Options

**Question**: Which Markdown editor library provides the best experience for blog post and job description authoring?

**Options evaluated**:

| Library | Stars | Bundle Size | Live Preview | Image Insert | Toolbar | Active Maintenance |
|---------|-------|-------------|--------------|--------------|---------|-------------------|
| MDXEditor | 2k+ | ~180KB gzip | Yes (WYSIWYG) | Yes (plugin) | Customizable | Active |
| react-md-editor | 2k+ | ~90KB gzip | Side-by-side | Manual | Basic | Moderate |
| react-simplemde-editor | 1k+ | ~120KB gzip | Side-by-side | Manual | Fixed | Low |
| TipTap (with Markdown) | 10k+ | ~200KB gzip | WYSIWYG | Plugin | Customizable | Active |

**Analysis**:

- **MDXEditor** provides a true WYSIWYG Markdown editing experience where users see formatted output as they type, not raw Markdown syntax. It supports toolbars for headings, bold, italic, lists, links, code blocks, and image insertion. The image plugin can be configured to use a custom upload handler, integrating directly with our media upload endpoint.

- **react-md-editor** provides a split-pane view (raw Markdown on left, rendered preview on right). Simpler to integrate but requires users to write raw Markdown. Good for developers, but less intuitive for content authoring.

- **TipTap** is a headless rich-text editor framework. Very powerful and customizable but significantly more setup effort. Stores content as a ProseMirror document by default; Markdown import/export requires additional configuration. Overkill for this use case.

**Decision**: **MDXEditor**. It provides the best balance of WYSIWYG experience, Markdown storage compatibility, image insertion support, and reasonable bundle size. The WYSIWYG approach matches the spec requirement for users to "use the rich text editor to format text" (FR-006) while still storing content as Markdown. Key plugins to enable:

- `headingsPlugin` -- H1 through H6
- `listsPlugin` -- ordered and unordered lists
- `quotePlugin` -- blockquotes
- `linkPlugin` -- hyperlinks
- `imagePlugin` -- image insertion (connected to media upload API)
- `codeBlockPlugin` -- code blocks with language selection
- `markdownShortcutPlugin` -- Markdown shortcuts for power users
- `toolbarPlugin` -- configurable toolbar

**Integration pattern**:

```tsx
import {
  MDXEditor,
  headingsPlugin,
  listsPlugin,
  quotePlugin,
  linkPlugin,
  imagePlugin,
  toolbarPlugin,
  codeBlockPlugin,
  BoldItalicUnderlineToggles,
  BlockTypeSelect,
  InsertImage,
} from '@mdxeditor/editor';

<MDXEditor
  markdown={content}
  onChange={setContent}
  plugins={[
    headingsPlugin(),
    listsPlugin(),
    quotePlugin(),
    linkPlugin(),
    imagePlugin({ imageUploadHandler: handleImageUpload }),
    codeBlockPlugin(),
    toolbarPlugin({
      toolbarContents: () => (
        <>
          <BoldItalicUnderlineToggles />
          <BlockTypeSelect />
          <InsertImage />
        </>
      ),
    }),
  ]}
/>
```

---

### 3. Image Variant Generation

**Question**: How to generate thumbnail, small, medium, and large image variants from uploaded originals?

**Options evaluated**:

| Library | Approach | Performance | Quality | API Simplicity |
|---------|----------|-------------|---------|----------------|
| Thumbnailator | Pure Java, BufferedImage | Good | Good (Lanczos) | Excellent (fluent API) |
| imgscalr | Pure Java, BufferedImage | Good | Good (multiple modes) | Good |
| ImageMagick (via im4java) | Native process | Best | Best | Moderate (requires binary) |
| Java AWT ImageIO | JDK built-in | Basic | Basic | Low-level |

**Analysis**:

- **Thumbnailator** provides a fluent Java API for resizing images. Single dependency, no native binaries. Supports JPEG, PNG, GIF. Uses Java 2D internally with quality scaling. API is extremely simple:

```java
Thumbnails.of(inputFile)
    .size(200, 200)
    .outputFormat("jpg")
    .outputQuality(0.85)
    .toFile(outputFile);
```

- **imgscalr** is similar in approach to Thumbnailator but has a more manual API. Requires explicit BufferedImage handling. Slightly more code for the same result.

- **ImageMagick** produces the best quality but requires a native binary installed in the Docker container, adding operational complexity. Overkill for the scale of this system (161 existing images, infrequent uploads).

**Decision**: **Thumbnailator** (`net.coobird:thumbnailator`). Simplest API, pure Java (no native dependencies), good quality output. Meets the spec requirement of generating variants within 10 seconds (SC-004).

**Variant sizes** (based on common responsive breakpoints and existing Strapi usage patterns):

| Variant | Max Width | Max Height | Quality | Use Case |
|---------|-----------|------------|---------|----------|
| thumbnail | 150px | 150px | 80% | Media library grid, search results |
| small | 300px | 300px | 85% | Blog listing cards, skill icons |
| medium | 600px | 600px | 85% | Blog content images, job logos |
| large | 1200px | 1200px | 90% | Featured blog images, full-width display |

**Storage approach**: Store variants alongside originals using a naming convention: `{id}_thumbnail.jpg`, `{id}_small.jpg`, `{id}_medium.jpg`, `{id}_large.jpg`. Store files on the local filesystem within a configurable media directory, served as static resources by Spring Boot. This is the simplest approach and sufficient for a single-instance personal website. MongoDB GridFS is an alternative if filesystem portability becomes a concern, but adds complexity.

---

### 4. Auth0 React SDK

**Question**: How to integrate Auth0 authentication in the React frontend for admin page protection?

**Findings**:

The `@auth0/auth0-react` SDK provides:
- `Auth0Provider` -- wraps the app with Auth0 context
- `useAuth0()` -- hook for login/logout state and token access
- `withAuthenticationRequired()` -- HOC to protect routes

**Integration pattern**:

```tsx
// main.tsx
import { Auth0Provider } from '@auth0/auth0-react';

<Auth0Provider
  domain={import.meta.env.VITE_AUTH0_DOMAIN}
  clientId={import.meta.env.VITE_AUTH0_CLIENT_ID}
  authorizationParams={{
    redirect_uri: window.location.origin,
    audience: import.meta.env.VITE_AUTH0_AUDIENCE,
  }}
>
  <App />
</Auth0Provider>
```

```tsx
// AdminLayout.tsx
import { withAuthenticationRequired, useAuth0 } from '@auth0/auth0-react';

const AdminLayout = () => {
  const { getAccessTokenSilently } = useAuth0();

  // Pass token to API calls
  const apiClient = useMemo(() => createAdminApi(getAccessTokenSilently), []);

  return <Outlet />;
};

export default withAuthenticationRequired(AdminLayout, {
  onRedirecting: () => <LoadingSpinner />,
});
```

**Token injection for API calls**:

```typescript
// adminApi.ts
export const createAdminApi = (getToken: () => Promise<string>) => ({
  async get(url: string) {
    const token = await getToken();
    return fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
  },
  // ... post, put, delete similarly
});
```

**Decision**: Use `@auth0/auth0-react` v2.x. Protect all `/admin/*` routes with `withAuthenticationRequired`. Inject access tokens into all admin API calls via the `getAccessTokenSilently()` hook. No role-based checks needed on the frontend since Auth0 is configured with no self-registration -- any authenticated user is an admin.

---

### 5. Data Migration Approach

**Question**: How to migrate existing content from the Strapi MongoDB backup into the new data model?

**Findings**:

The backup at `/Users/simonrowe/backups/strapi-backup-20251116_170434/` contains:
- **MongoDB BSON files**: `blogs.bson` (18), `jobs.bson` (9), `skills.bson` (71), `skills_groups.bson` (9), `profiles.bson` (1), `social_medias.bson` (4), `tags.bson` (26), `tour-steps.bson` (7)
- **Media files**: 161 uploaded files in `strapi-uploads/`

**Key observations from backup data**:

1. **ObjectId references**: Strapi uses MongoDB ObjectId strings for cross-references (e.g., blog `tags` array contains ObjectId strings referencing `tags` collection). These must be mapped to new IDs during migration.

2. **Skill-to-SkillGroup**: The `skills_groups` collection contains a `skills` array of ObjectId references. Individual `skills` documents do not reference their parent group. The relationship is group-to-skills (one-to-many via embedded array).

3. **Blog-to-Tags**: The `blogs` collection contains a `tags` array of ObjectId references.

4. **Blog/Job images**: Referenced by ObjectId pointing to `upload_file` collection entries.

5. **Content format**: Blog `content` and job `longDescription` are already Markdown strings. No conversion needed.

6. **Date formats**: Job dates are ISO date strings (`"2019-04-15"`). Timestamps use ISO datetime format.

**Migration strategy**:

1. **Phase 1 -- Schema mapping**: Define a mapping from Strapi field names to new model field names. Most are straightforward renames.

2. **Phase 2 -- ID mapping**: Create an old-ID-to-new-ID lookup table during migration. Process entities in dependency order:
   1. Tags (no dependencies)
   2. Media assets / upload files (no dependencies, copy files)
   3. Skills (no dependencies except images)
   4. Skill Groups (depends on skills)
   5. Profile (depends on images)
   6. Social Media Links (no dependencies)
   7. Jobs (depends on skills, images)
   8. Blogs (depends on tags, skills, images)
   9. Tour Steps (depends on images)

3. **Phase 3 -- Media migration**: Copy files from `strapi-uploads/` to the new media directory. Generate image variants for each file.

**Implementation approach**: A Spring Boot `CommandLineRunner` or `ApplicationRunner` activated by a Spring profile (`--spring.profiles.active=migrate`). Reads BSON files directly using the `org.bson` library (already available via Spring Data MongoDB dependency), maps to new domain objects, and persists via repositories. This runs once and can be re-run idempotently (upsert by old Strapi ID stored in a `legacyId` field).

**Decision**: Implement as a `DataMigrationService` activated by Spring profile. Use `org.bson.BsonDocument` for BSON parsing. Store `legacyId` on migrated entities for idempotent re-runs. Process in dependency order. Generate image variants during migration for all media assets.

---

### 6. Media Storage Strategy

**Question**: Where and how to store uploaded images and generated variants?

**Options**:

| Approach | Pros | Cons |
|----------|------|------|
| Local filesystem + static serving | Simplest, fast reads, works with Thumbnailator directly | Not portable across hosts, manual backup needed |
| MongoDB GridFS | Portable with DB, backed up with DB | More complex, slower for serving, requires GridFS API |
| S3-compatible object storage | Scalable, CDN-friendly | External dependency, cost, overkill for personal site |

**Decision**: **Local filesystem** with Spring Boot static resource serving. Configure a media directory outside the application JAR (e.g., `/data/media/`) and serve it via `spring.web.resources.static-locations`. This is the simplest approach per Constitution Principle V. The Docker volume mount ensures persistence across container restarts. The media directory is included in the Docker Compose volume configuration.

**Serving configuration**:

```yaml
spring:
  web:
    resources:
      static-locations:
        - classpath:/static/
        - file:${MEDIA_DIR:/data/media/}
```

**URL pattern**: `/media/{id}_{variant}.{ext}` (e.g., `/media/abc123_thumbnail.jpg`)

---

## Research Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Backend auth | Spring Security 6 OAuth2 Resource Server + custom Auth0 AudienceValidator | First-class Spring support, no additional SDK, stateless JWT |
| Markdown editor | MDXEditor | WYSIWYG experience, Markdown storage, image plugin, reasonable bundle |
| Image processing | Thumbnailator | Pure Java, fluent API, good quality, no native deps |
| Frontend auth | @auth0/auth0-react v2.x | Official SDK, React hooks, route protection HOC |
| Data migration | Spring Boot CommandLineRunner with BSON parsing | Uses existing Spring Data MongoDB dependency, one-time run |
| Media storage | Local filesystem + Spring Boot static serving | Simplest approach, sufficient for single-instance personal site |
| Image variants | 4 sizes (thumbnail 150px, small 300px, medium 600px, large 1200px) | Covers all display contexts identified in spec |
