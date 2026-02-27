// Migration script: Add blog post series — Rebuilding simonrowe.dev with AI
// Runs via mongosh inside the MongoDB container
// Idempotent — safe to run multiple times

const db = db.getSiblingDB('simonrowe');

// ============================================================================
// Idempotency check
// ============================================================================

const existingPost = db.blogs.findOne({ title: 'From Zero to Specification: How I Used AI to Plan My Entire Website Rebuild' });
if (existingPost) {
  print('--- Blog post series already exists. Skipping migration. ---');
  print('Found: ' + existingPost.title);
  print('Total published blogs: ' + db.blogs.countDocuments({ published: true }));
  quit();
}

print('=== Adding blog post series: Rebuilding simonrowe.dev with AI ===');
print('');

// ============================================================================
// Phase 1: Insert new tags (upsert to skip existing)
// ============================================================================

print('--- Inserting new tags ---');

const newTags = [
  'SpecKit',
  'Conductor',
  'Spec-Driven Development',
  'AI Productivity',
  'Parallel Development',
  'Data Migration',
  'Retrospective',
  'AI'
];

let tagsInserted = 0;
let tagsSkipped = 0;

for (const tagName of newTags) {
  const result = db.tags.updateOne(
    { name: tagName },
    { $setOnInsert: { name: tagName } },
    { upsert: true }
  );
  if (result.upsertedCount > 0) {
    tagsInserted++;
    print('  Inserted tag: ' + tagName);
  } else {
    tagsSkipped++;
    print('  Tag already exists: ' + tagName);
  }
}

print('Tags inserted: ' + tagsInserted + ', skipped: ' + tagsSkipped);

// ============================================================================
// Phase 2: Resolve tag references
// ============================================================================

print('');
print('--- Resolving tag references ---');

const allTagNames = [
  // New tags
  'SpecKit', 'Conductor', 'Spec-Driven Development', 'AI Productivity',
  'Parallel Development', 'Data Migration', 'Retrospective', 'AI',
  // Existing tags needed by blog posts
  'Spring', 'React', 'MongoDB'
];

const tagsByName = {};
let resolvedTags = 0;

for (const name of allTagNames) {
  const tag = db.tags.findOne({ name: name });
  if (tag) {
    tagsByName[name] = tag._id;
    resolvedTags++;
  } else {
    print('  WARNING: Tag not found: ' + name);
  }
}

print('Resolved ' + resolvedTags + ' of ' + allTagNames.length + ' tags');

function tagRef(name) {
  return { "$ref": "tags", "$id": tagsByName[name] };
}

// ============================================================================
// Phase 3: Ensure skills exist in the flat skills collection and resolve refs
// ============================================================================

print('');
print('--- Ensuring skills exist in flat skills collection ---');

// Java 17 and Java 21 exist in skill_groups but not in the flat skills collection.
// Blog @DBRef references point to the skills collection, so we need them there.
const skillsToEnsure = ['Java 17', 'Java 21'];
let skillsInserted = 0;

for (const name of skillsToEnsure) {
  const existing = db.skills.findOne({ name: name });
  if (existing) {
    print('  Skill already exists: ' + name);
  } else {
    // Copy _id from skill_groups embedded skill to keep consistency
    let embeddedId = null;
    const groups = db.skill_groups.find({ 'skills.name': name }).toArray();
    for (const g of groups) {
      const match = (g.skills || []).find(s => s.name === name);
      if (match) { embeddedId = match._id; break; }
    }
    db.skills.insertOne({ _id: embeddedId || new ObjectId(), name: name });
    skillsInserted++;
    print('  Inserted skill: ' + name);
  }
}

if (skillsInserted > 0) {
  print('Skills inserted into flat collection: ' + skillsInserted);
}

print('');
print('--- Resolving skill references ---');

const allSkillNames = [
  'Java 21', 'Spring Boot', 'React', 'MongoDB', 'Docker',
  'Typescript', 'Elastic Search', 'Javascript', 'Kafka'
];

const skillsByName = {};
let resolvedSkills = 0;

for (const name of allSkillNames) {
  const skill = db.skills.findOne({ name: name });
  if (skill) {
    skillsByName[name] = skill._id;
    resolvedSkills++;
  } else {
    print('  WARNING: Skill not found: ' + name);
  }
}

print('Resolved ' + resolvedSkills + ' of ' + allSkillNames.length + ' skills');

function skillRef(name) {
  return { "$ref": "skills", "$id": skillsByName[name] };
}

// ============================================================================
// Phase 4: Blog post content
// ============================================================================

print('');
print('--- Preparing blog post content ---');

// ---------------------------------------------------------------------------
// Post 1: From Zero to Specification
// ---------------------------------------------------------------------------

const post1Content = `# From Zero to Specification: How I Used AI to Plan My Entire Website Rebuild

Every developer has that personal website. The one you built years ago, maybe with a CMS you no longer love, running on infrastructure you'd rather not maintain. Mine was built on Strapi with a React frontend — functional, but creaking at the seams. When I decided to rebuild it from scratch using a modern Spring Boot + React stack, I faced a familiar question: **where do you even start?**

The answer, it turns out, wasn't writing code. It was writing specifications.

## The Problem with "Vibe Coding"

There's a temptation when working with AI coding agents to just... start. Open a chat, describe what you want, and let the AI generate code. This approach — sometimes called "vibe coding" — works brilliantly for small, isolated tasks. But for rebuilding an entire website with multiple interconnected features? It falls apart fast.

Without a plan, AI agents:
- Make inconsistent architectural decisions across features
- Choose different patterns for similar problems
- Miss integration points between components
- Generate code that works in isolation but conflicts when combined

I needed something better. I needed a spec-driven approach.

## Enter SpecKit

[SpecKit](https://github.com/github/spec-kit) is a specification toolkit that provides a structured workflow for planning features before implementing them. It uses a series of slash commands that guide you through a deliberate process:

\`\`\`
/speckit.specify  →  Create feature specification (WHAT and WHY)
/speckit.plan     →  Generate implementation plan (HOW)
/speckit.tasks    →  Break down into executable tasks
/speckit.implement →  Execute the task plan
\`\`\`

Each command builds on the previous one, creating a chain of artifacts that give AI agents the context they need to write consistent, well-architected code.

## Planning Nine Features

Over the course of a single session, I specified nine features for the website rebuild:

\`\`\`
specs/
├── 001-project-infrastructure/
│   ├── spec.md
│   ├── plan.md
│   └── tasks.md
├── 002-profile-homepage/
├── 003-blog-system/
├── 004-skills-employment/
├── 005-site-search/
├── 006-contact-form/
├── 007-content-management/
├── 008-interactive-tour/
└── 009-global-job/
\`\`\`

Each specification followed the same template structure, ensuring consistency across all features. Here's what an acceptance scenario looked like in the spec:

\`\`\`markdown
### Scenario: Visitor reads a blog post
**Given** a visitor navigates to the blog listing page
**When** they click on a blog post title
**Then** the full blog post content renders with:
  - Markdown formatting preserved
  - Code blocks with syntax highlighting
  - Featured image displayed
  - Tags and publication date visible
\`\`\`

This format gave the AI agents clear, testable criteria to work against. No ambiguity about what "done" looks like.

## The Power of User Stories

Each spec contained prioritised user stories that defined the feature from the user's perspective. For example, the blog system spec included:

\`\`\`markdown
## User Stories

### US-1: Read Blog Posts (P1 — MVP)
As a visitor, I want to read published blog posts with rich
formatting so that I can learn from Simon's technical articles.

### US-2: Browse by Tag (P2)
As a visitor, I want to filter blog posts by topic tags so that
I can find articles relevant to my interests.

### US-3: Search Blog Content (P3)
As a visitor, I want to search across all blog post content so
that I can find specific topics or code examples.
\`\`\`

By prioritising stories (P1, P2, P3), the implementation plan knew exactly which features to build first. The MVP was always clear: deliver the P1 stories, validate them, then move on.

## Why Specification First Beats Vibe Coding

After specifying all nine features, I had:

- **45+ user stories** with clear acceptance criteria
- **Consistent data models** across features (blogs reference tags and skills the same way everywhere)
- **Clear integration points** (site search knows about blogs, skills, and jobs because the specs define it)
- **A dependency graph** showing which features can be built in parallel

None of this required writing a single line of code. But it saved enormous amounts of time when the coding started, because every AI agent had the same architectural context.

## The Specification as a Contract

Perhaps the most valuable aspect of spec-driven development is that the specification becomes a **contract between you and the AI agent**. When you tell an agent to implement a feature "per the spec," it has:

1. **Clear boundaries** — what's in scope and what isn't
2. **Testable criteria** — how to verify the implementation works
3. **Architectural context** — how this feature fits with everything else
4. **Data model awareness** — what entities exist and how they relate

This is fundamentally different from a chat-based prompt like "build me a blog system." The spec provides the depth and precision that AI agents need to produce production-quality code.

## What's Next

With nine specifications written, the real fun begins. In the [next post](/blogs), I'll cover how I went from specifications to a working infrastructure skeleton in a single weekend — and how [Conductor](https://www.conductor.build/) enabled me to run multiple AI agents in parallel, each working on a different feature branch.

The key takeaway: **invest time in planning, and the implementation becomes almost mechanical.** AI agents are incredibly powerful code generators, but they need clear instructions. SpecKit provides the framework for creating those instructions systematically.

---

*This is Part 1 of a 5-part series on rebuilding simonrowe.dev with AI coding agents. [Read Part 2 →](/blogs)*`;

// ---------------------------------------------------------------------------
// Post 2: Building the Foundation
// ---------------------------------------------------------------------------

const post2Content = `# Building the Foundation: Infrastructure and First Features in a Weekend

With nine feature specifications written (see [Part 1](/blogs)), it was time to start building. Over the weekend of February 23-24, I went from an empty repository to a fully functional monorepo with a profile homepage and blog system — all powered by AI coding agents and [Conductor](https://www.conductor.build/).

## The Monorepo Decision

The first architectural decision was the project structure. A monorepo with \`backend/\` and \`frontend/\` directories made sense for a personal website:

- **Backend**: Spring Boot 3.5 with Java 21, Spring Data MongoDB, Elasticsearch
- **Frontend**: React with TypeScript, Vite, react-markdown for blog rendering
- **Infrastructure**: MongoDB 8, Elasticsearch 8.17, Kafka (for future event-driven features)

Everything runs locally via Docker Compose, making the development experience simple and reproducible.

## The Blog Entity: Java Records Meet MongoDB

One of the first things to build was the blog data model. Spring Boot's support for Java records combined with Spring Data MongoDB annotations made this remarkably clean:

\`\`\`java
@Document(collection = "blogs")
@CompoundIndex(name = "idx_published_created",
               def = "{'published': 1, 'createdDate': -1}")
public record Blog(
    @Id String id,
    String title,
    String shortDescription,
    String content,
    boolean published,
    String featuredImageUrl,
    @Field("createdDate") Instant createdDate,
    @Field("updatedDate") Instant updatedDate,
    @DBRef List<Tag> tags,
    @DBRef List<Skill> skills
) {}
\`\`\`

Notice the \`@DBRef\` annotations — these create document references to the \`tags\` and \`skills\` collections rather than embedding the data. This means updating a tag name automatically reflects across all blog posts that reference it.

## Docker Compose: The Local Stack

The infrastructure skeleton included a Docker Compose configuration for all services:

\`\`\`yaml
services:
  mongodb:
    image: mongo:8
    ports:
      - "27017:27017"
    volumes:
      - mongodb-data:/data/db
    environment:
      MONGO_INITDB_DATABASE: simonrowe

  elasticsearch:
    image: elasticsearch:8.17.0
    ports:
      - "9200:9200"
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: "-Xms512m -Xmx512m"
    volumes:
      - elasticsearch-data:/usr/share/elasticsearch/data
\`\`\`

Simple, reproducible, and everything a developer needs to get started with \`docker compose up -d\`.

## Introducing Conductor

Here's where things got interesting. [Conductor](https://www.conductor.build/) is a Mac app that lets you run multiple AI coding agents in parallel, each in its own isolated git worktree. Think of it as having multiple developers working on different feature branches simultaneously.

While one agent was building the infrastructure skeleton (PR #2), I could spin up another workspace in Conductor to start on the profile homepage (PR #3). Each agent had its own copy of the repository, its own branch, and couldn't interfere with the other.

## The React Frontend

The frontend used a component-based architecture with React and TypeScript. Here's the BlogCard component that renders each post in the listing:

\`\`\`tsx
export function BlogCard({ blog, imagePosition }: BlogCardProps) {
  const imageUrl = blog.featuredImageUrl ?? PLACEHOLDER_IMAGE
  const formattedDate = formatDate(blog.createdDate)

  return (
    <article className={\`blog-card blog-card--image-\${imagePosition}\`}>
      <Link aria-label={blog.title} to={\`/blogs/\${blog.id}\`}>
        <div className="blog-card__image-wrapper">
          <img alt={blog.title} loading="lazy" src={imageUrl} />
        </div>
        <div className="blog-card__content">
          <h2 className="blog-card__title">{blog.title}</h2>
          <p className="blog-card__description">
            {blog.shortDescription}
          </p>
          <time dateTime={blog.createdDate}>{formattedDate}</time>
          {blog.tags.length > 0 && (
            <ul aria-label="Tags" className="blog-card__tags">
              {blog.tags.map((tag) => (
                <li key={tag.name}>{tag.name}</li>
              ))}
            </ul>
          )}
        </div>
      </Link>
    </article>
  )
}
\`\`\`

The AI agent generated this with proper accessibility attributes (\`aria-label\`, semantic HTML), lazy loading for images, and a clean BEM naming convention — all because the spec defined these as requirements.

## Weekend Results

By Sunday evening, I had:

- **PR #2**: Infrastructure skeleton — Docker Compose, Gradle multi-project build, Spring Boot application with MongoDB and Elasticsearch
- **PR #3**: Profile homepage — dynamic profile data from MongoDB, social media links, responsive layout
- **PR #5**: Blog system — full CRUD-ready blog API, markdown rendering with syntax highlighting, tag and skill relationships

Three pull requests, three features, all reviewed and merged. The specifications ensured consistency: the blog system used the same MongoDB connection patterns as the profile page, the same error handling approach, the same API response format.

## How Conductor Changed the Workflow

Without Conductor, I would have worked on features sequentially: finish infrastructure, then profile, then blog. With Conductor's parallel workspaces, I could:

1. Start the infrastructure agent in Workspace 1
2. While it was working, start the profile homepage agent in Workspace 2
3. Review and merge infrastructure first
4. The profile agent could rebase on the updated main branch
5. Start the blog system agent in Workspace 3

This parallel workflow compressed what might have been a week of sequential work into a single weekend. The key insight: **AI agents don't need breaks, and with isolated worktrees, they don't create merge conflicts.**

## Lessons from the Weekend

The most surprising lesson was how well the spec-driven approach translated to parallel execution. Because each specification was self-contained with clear boundaries, the agents rarely made conflicting assumptions. The infrastructure spec defined the Docker Compose services, the profile spec consumed them, and the blog spec extended them — all without stepping on each other's toes.

## What's Next

The foundation was solid, but the real test was coming. In [Part 3](/blogs), I'll cover the most intense day of the rebuild — shipping six features in a single day using Conductor's full parallel capability. Skills, employment history, site search, contact form, and more — all landing on the same day.

---

*This is Part 2 of a 5-part series on rebuilding simonrowe.dev with AI coding agents. [Read Part 3 →](/blogs)*`;

// ---------------------------------------------------------------------------
// Post 3: Shipping Six Features in a Day
// ---------------------------------------------------------------------------

const post3Content = `# Shipping Six Features in a Day: Parallel AI Agents with Conductor

February 24th was the most productive single day of the entire rebuild. Six pull requests landed — skills and employment history, the blog system, site search with Elasticsearch, a contact form, test infrastructure, and more. All built by AI agents running in parallel through [Conductor](https://www.conductor.build/).

## The Morning Sprint

The day started with four Conductor workspaces running simultaneously:

- **Workspace 1**: Skills and employment features (PR #4) — skill groups with ratings, job history with rich descriptions
- **Workspace 2**: Blog system enhancements (PR #5) — search integration, tag filtering, featured images
- **Workspace 3**: Site search with Elasticsearch (PR #7) — full-text search across blogs, jobs, and skills
- **Workspace 4**: Contact form (PR #8) — email validation, reCAPTCHA, server-side spam protection

Each workspace had its own isolated git worktree. Each agent had the full specification context. They worked independently, and I reviewed PRs as they completed.

## Elasticsearch Integration

The site search feature (PR #7) was one of the more technically interesting implementations. It required indexing content from three different MongoDB collections into Elasticsearch for unified search.

The blog index settings used a custom analyzer:

\`\`\`json
{
  "number_of_shards": 1,
  "number_of_replicas": 0,
  "analysis": {
    "analyzer": {
      "blog_analyzer": {
        "type": "custom",
        "tokenizer": "standard",
        "filter": ["lowercase", "asciifolding"]
      }
    }
  }
}
\`\`\`

The \`IndexService\` handled the mapping from MongoDB documents to Elasticsearch search documents:

\`\`\`java
public BlogSearchDocument blogToBlogDocument(final Blog blog) {
    List<String> tagNames = blog.tags() == null
        ? List.of()
        : blog.tags().stream().map(Tag::name).toList();

    List<String> skillNames = blog.skills() == null
        ? List.of()
        : blog.skills().stream()
            .map(com.simonrowe.blog.Skill::name).toList();

    return new BlogSearchDocument(
        blog.id(),
        blog.title(),
        blog.shortDescription(),
        blog.content(),
        tagNames,
        skillNames,
        blog.featuredImageUrl(),
        blog.createdDate(),
        "/blogs/" + blog.id()
    );
}
\`\`\`

This method extracts tag and skill names from DBRef-resolved objects and creates a flat search document. The \`fullSyncBlogIndex()\` method then bulk-indexes all published blogs on application startup, ensuring the search index always reflects the current state of MongoDB.

## The Contact Form: React Hook Form + Zod

The contact form (PR #8) showcased modern React form handling with type-safe validation:

\`\`\`tsx
export function ContactForm() {
  const recaptchaRef = useRef<ReCAPTCHA>(null)
  const [submitSuccess, setSubmitSuccess] = useState(false)

  const {
    register,
    handleSubmit,
    setValue,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ContactFormData>({
    resolver: zodResolver(
      buildContactFormSchema(!!recaptchaSiteKey)
    ),
    defaultValues: {
      firstName: '', lastName: '', email: '',
      subject: '', message: '', recaptchaToken: '',
    },
  })

  const onSubmit = async (data: ContactFormData) => {
    await submitContactForm(data)
    setSubmitSuccess(true)
    reset()
    recaptchaRef.current?.reset()
  }
  // ...
}
\`\`\`

The Zod schema provided runtime type validation that matched the TypeScript types — no discrepancy between what the form accepts and what the API expects. The AI agent chose this pattern because the spec mentioned "robust form validation with clear error messages."

## Managing Parallel Agents

Running four agents simultaneously isn't without challenges. Here are the key lessons from the experience:

### When to Run in Parallel

Agents work best in parallel when features are **truly independent** — different database collections, different API endpoints, different frontend pages. The skills feature and the contact form had zero overlap, making them perfect candidates for parallel development.

### When to Run Sequentially

Features that share infrastructure should be built sequentially. The site search feature depended on the blog system's data model, so it waited for the blog PR to merge first. Trying to build both simultaneously would have created conflicting assumptions about the blog entity structure.

### The Review Bottleneck

The biggest constraint wasn't the AI agents — it was me. With four PRs arriving within hours of each other, reviewing became the bottleneck. Each PR needed careful review for:

- **Consistency** with the specification
- **Integration** with existing code
- **Security** issues (API keys, input validation, CORS)
- **Test coverage** and edge cases

I developed a rhythm: review the simplest PR first, merge it, then review the next one. This kept the main branch moving forward and gave later PRs a stable base to rebase against.

### Merge Conflict Management

Conductor's isolated worktrees eliminated most merge conflicts, but they could still occur when two features touched shared files like \`App.tsx\` (adding routes) or \`docker-compose.yml\` (adding services). The solution: merge features one at a time, and let subsequent features rebase on the updated main.

## A Test Infrastructure PR

Amidst the feature work, PR #6 landed a test infrastructure chore — setting up JUnit 5, Mockito, and Testcontainers for integration testing. This wasn't a user-facing feature, but it was essential infrastructure that all subsequent features benefited from.

## End of Day Tally

By the end of February 24th:

| PR | Feature | Lines Changed |
|----|---------|--------------|
| #4 | Skills & Employment | ~1,200 |
| #5 | Blog System | ~800 |
| #6 | Test Infrastructure | ~400 |
| #7 | Site Search | ~1,500 |
| #8 | Contact Form | ~1,000 |

That's roughly 5,000 lines of production code across five PRs in a single day. Not all of it was perfect — there were integration issues to fix later (see [Part 4](/blogs)) — but the core functionality was solid.

## The Conductor Advantage

The key insight from this day: **parallelism is the superpower**. A single developer with a single AI agent might ship one feature per day. With Conductor, I shipped five. The math is simple but powerful — if features are independent, you can scale horizontally.

Conductor made this possible by handling the complexity of git worktrees, branch management, and workspace isolation. I didn't need to think about git mechanics; I just opened a new workspace, pointed the agent at a spec, and let it work.

## What's Next

Six features in a day sounds impressive, but the work wasn't done. In [Part 4](/blogs), I'll cover the integration challenges that followed — migrating data from Strapi, building an interactive tour, and fixing the CORS issues that inevitably arise when frontend and backend are developed in parallel by different agents.

---

*This is Part 3 of a 5-part series on rebuilding simonrowe.dev with AI coding agents. [Read Part 4 →](/blogs)*`;

// ---------------------------------------------------------------------------
// Post 4: Interactive Tours, Data Migration, and the Finishing Touches
// ---------------------------------------------------------------------------

const post4Content = `# Interactive Tours, Data Migration, and the Finishing Touches

After the whirlwind of shipping six features in a day (see [Part 3](/blogs)), the next phase was about polish and integration. This meant building an interactive tour for first-time visitors, migrating 18 existing blog posts from Strapi, fixing the inevitable CORS issues, and adding the final job position with AI skills. This is the messy, real-world part that AI agents sometimes struggle with.

## The Interactive Tour (PR #9)

One feature I was excited about was an interactive tour that guides first-time visitors through the site. The tour highlights key sections — the profile banner, blog posts, skills — with overlay tooltips and smooth scrolling.

The data model was simple but effective:

\`\`\`java
@Document(collection = "tourSteps")
public record TourStep(
    @Id String id,
    @Indexed(unique = true) int order,
    String targetSelector,
    String title,
    String titleImage,
    String description,
    String position
) {}
\`\`\`

Each tour step targets a CSS selector on the page (\`targetSelector\`), shows a title and description in a tooltip, and positions itself relative to the element (\`top\`, \`bottom\`, \`left\`, \`right\`). The \`order\` field determines the sequence, and the unique index ensures no two steps share the same position in the tour.

The frontend implementation used a combination of \`getBoundingClientRect()\` for positioning, \`scrollIntoView()\` for smooth navigation, and a semi-transparent overlay to focus attention on each highlighted element.

## The CORS Fix (PR #10)

Here's a real-world integration challenge. The backend and frontend were developed in separate Conductor workspaces by different agents. The backend agent configured the API on port 8080. The frontend agent pointed API calls at the default Vite dev server URL. Neither agent knew about the other's CORS requirements.

The fix was straightforward once diagnosed:

\`\`\`java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("\${cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!allowedOrigins.isBlank()) {
            registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT",
                                "DELETE", "OPTIONS");
        }
    }
}
\`\`\`

The key decision was making CORS configurable via \`cors.allowed-origins\` in \`application.yml\` rather than hardcoding values. This means the same code works in development (\`http://localhost:3000\`), staging, and production without changes.

**Lesson learned**: when running parallel agents, integration configuration is the most common source of issues. Each agent makes reasonable assumptions about its environment, but those assumptions don't always align.

## Migrating from Strapi

The biggest data challenge was migrating 18 existing blog posts from my old Strapi CMS to the new Spring Boot stack. The Strapi data lived in a MongoDB backup with a different schema — different field names, different relationship formats, different image handling.

The migration script transformed each blog post from Strapi's format to the new schema:

\`\`\`javascript
// From migrate-strapi-data.js
src.blogs.find().forEach(b => {
  const tagRefs = (b.tags || []).map(tagId => ({
    "$ref": "tags",
    "$id": tagId
  }));
  const skillRefs = (b.skills || []).map(skillId => ({
    "$ref": "skills",
    "$id": skillId
  }));

  dst.blogs.insertOne({
    _id: b._id,
    title: b.title,
    shortDescription: b.shortDescription || null,
    content: b.content || null,
    published: b.published || false,
    featuredImageUrl: resolveImageUrl(b.image),
    createdDate: b.createdAt,
    updatedDate: b.updatedAt,
    tags: tagRefs,
    skills: skillRefs
  });
});
\`\`\`

The critical detail here is the DBRef format: \`{ "$ref": "tags", "$id": tagId }\`. Spring Data MongoDB expects this exact structure to resolve \`@DBRef\` annotations. Getting the \`$ref\` collection name wrong (e.g., using \`"tag"\` instead of \`"tags"\`) causes silent failures where the reference resolves to \`null\`.

## The Global Job Position (PR #12)

The final feature added my current role — Head of Engineering at Global — with a comprehensive set of AI-related skills. This PR (PR #12) demonstrated the migration script pattern that all data seeding followed:

1. **Shell wrapper** (\`seed-global-job.sh\`): copies assets, finds the MongoDB container, executes the migration
2. **Migration script** (\`add-global-job-data.js\`): idempotent JavaScript run via \`mongosh\`

The script also added a new "Artificial Intelligence" skill group with skills like Claude Code, GitHub Copilot, AI-Assisted Development, Prompt Engineering, and MCP (Model Context Protocol). These skills link to the job via name-based references, consistent with the existing Strapi data model.

## Real-World Integration Challenges

Working with AI agents on integration tasks revealed several patterns:

### What AI agents handle well:
- **Isolated features**: When the spec clearly defines inputs and outputs, agents produce clean, well-tested code
- **Data transformations**: Migration scripts, format conversions, and schema mappings
- **Boilerplate**: Entity classes, repository interfaces, controller endpoints, test setup

### Where AI agents struggle:
- **Cross-feature configuration**: CORS, environment variables, shared constants that span frontend and backend
- **Runtime environment assumptions**: Port numbers, file paths, container names
- **Subtle data format issues**: DBRef \`$ref\` values, date format mismatches, null handling edge cases

The solution isn't to avoid AI agents for integration work — it's to **be explicit in your specifications** about integration points. If the spec says "the frontend calls the backend at \`/api/blogs\` on the configured base URL," the agent knows to make the base URL configurable rather than hardcoding it.

## The Power of Idempotent Scripts

Every migration script in this project is idempotent — running it twice produces the same result as running it once. This pattern is essential when working with AI agents because:

1. **Safe iteration**: If a migration partially fails, you can fix the issue and re-run
2. **Team-friendly**: Any developer can run the seed scripts without worrying about duplicate data
3. **CI-compatible**: Scripts can run in automated pipelines without special handling

The pattern is simple: check if the target data already exists before inserting.

## What's Next

With the tour, data migration, CORS fixes, and final job position all in place, the site was functionally complete. In [Part 5](/blogs), I'll share an honest retrospective on the entire rebuild — what worked, what didn't, and practical advice for anyone considering this approach.

---

*This is Part 4 of a 5-part series on rebuilding simonrowe.dev with AI coding agents. [Read Part 5 →](/blogs)*`;

// ---------------------------------------------------------------------------
// Post 5: Lessons Learned
// ---------------------------------------------------------------------------

const post5Content = `# Lessons Learned: What Worked, What Didn't, and What's Next

This is the honest retrospective. After rebuilding simonrowe.dev from scratch using AI coding agents, [SpecKit](https://github.com/github/spec-kit) for specification-driven development, and [Conductor](https://www.conductor.build/) for parallel agent workspaces, here's what I've learned — the wins, the struggles, and the roadmap ahead.

## By the Numbers

Before diving into the qualitative lessons, here's what the rebuild produced:

\`\`\`
Project Statistics
─────────────────────────────────
Backend (Java)          ~5,800 lines
Frontend (TypeScript)   ~3,500 lines
Test files                    23
Feature specifications         9
Pull requests                 12
Migration scripts              3
Duration                  5 days
─────────────────────────────────
\`\`\`

Nine features, from infrastructure to interactive tours, built and deployed in under a week. Not all of these were complex — some were straightforward CRUD — but the aggregate output is significant for a solo developer.

## What Worked Well

### 1. Spec-Driven Development Keeps Agents Focused

The single biggest win was writing specifications before code. When an AI agent has a clear spec with user stories, acceptance criteria, and a data model, it produces dramatically better code than when given a vague prompt.

The spec acts as a **guardrail**: the agent knows what to build, what not to build, and how to verify its work. Without specs, I found agents would over-engineer solutions, add features I didn't ask for, or make inconsistent architectural choices.

### 2. AI Excels at Boilerplate and Scaffolding

AI agents are remarkably good at:
- **Entity classes and data models** — the Blog, Tag, Skill, TourStep records were all generated correctly on the first try
- **Repository interfaces** — Spring Data MongoDB repositories with custom query methods
- **Controller endpoints** — REST controllers with proper HTTP status codes, validation, and error handling
- **Test setup** — Mockito-based unit tests and Testcontainers integration tests

Here's an example of a Testcontainers integration test the AI generated:

\`\`\`java
@DataMongoTest
@Testcontainers
class TourStepRepositoryTest {

    @Container
    static MongoDBContainer mongodb =
        new MongoDBContainer("mongo:8");

    @Autowired
    private TourStepRepository tourStepRepository;

    @DynamicPropertySource
    static void configureProperties(
            final DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",
                      mongodb::getReplicaSetUrl);
    }

    @Test
    void findAllByOrderByOrderAscReturnsSortedSteps() {
        tourStepRepository.saveAll(List.of(
            new TourStep("s-3", 3, ".contact",
                         "Contact", null,
                         "Contact desc", "top"),
            new TourStep("s-1", 1, ".banner",
                         "Welcome", null,
                         "Welcome desc", "bottom"),
            new TourStep("s-2", 2, ".about",
                         "About", null,
                         "About desc", "top")
        ));

        final List<TourStep> steps =
            tourStepRepository.findAllByOrderByOrderAsc();

        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).order()).isEqualTo(1);
        assertThat(steps.get(0).title()).isEqualTo("Welcome");
    }
}
\`\`\`

This test uses Testcontainers to spin up a real MongoDB 8 instance, inserts documents out of order, and verifies the repository returns them sorted. Clean, readable, and it works on any machine with Docker.

### 3. Conductor Enables Massive Parallelism

Running multiple agents simultaneously through Conductor was the productivity multiplier. On the peak day (February 24th), I had four agents working on different features in parallel. Each had its own isolated git worktree, its own branch, and its own conversation context.

The total throughput was roughly 4x what a single agent could achieve — but the real benefit was the workflow. Instead of waiting for one feature to complete before starting the next, I could review completed PRs while new features were being built.

## What Didn't Work Well

### 1. Complex Integration Bugs

When features developed by different agents needed to work together, integration issues were common:
- **CORS misconfiguration** between frontend and backend agents
- **API base URL** assumptions that differed between development and Docker environments
- **Shared constants** (collection names, index names) that needed to match across services

These aren't AI-specific problems — they happen with human developers too. But AI agents are less likely to proactively think about how their code integrates with code they haven't seen.

### 2. Occasional Hallucinated Imports

AI agents sometimes imported libraries or used APIs that didn't exist in the project. For example, an agent might import a utility class from a package that was available in its training data but not included in this project's dependencies. These issues were always caught during review, but they added friction.

### 3. Merge Conflicts from Parallel Agents

Even with isolated worktrees, merge conflicts occurred when multiple agents modified shared files like:
- \`App.tsx\` (adding new routes)
- \`docker-compose.yml\` (adding new services)
- \`build.gradle\` (adding dependencies)

The solution was to merge features one at a time and rebase subsequent branches, but this required manual intervention and sometimes re-running parts of the agent's work.

## The CI Pipeline

Every PR ran through a comprehensive CI pipeline that caught issues before merge:

\`\`\`yaml
# .github/workflows/ci.yml
jobs:
  backend:
    name: Backend Build & Test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Run Checkstyle
        run: ./gradlew :backend:checkstyleMain
      - name: Run tests
        run: ./gradlew :backend:test
      - name: Verify coverage
        run: ./gradlew :backend:jacocoTestCoverageVerification

  frontend:
    name: Frontend Build & Test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
      - run: npm ci
      - run: npm test
      - run: npm run build
\`\`\`

This pipeline enforces Checkstyle compliance, runs all tests with JaCoCo coverage verification, and builds the frontend — catching issues regardless of whether a human or AI wrote the code.

## Practical Tips

If you're considering a similar approach, here are my recommendations:

1. **Write specs first, always.** The 30 minutes you spend on a specification saves hours of iteration with AI agents.

2. **Use structured specifications.** SpecKit's template gives every feature the same structure — user stories, acceptance criteria, data models. Consistency helps agents produce consistent code.

3. **Run parallel agents only for independent features.** If two features share a database collection or API endpoint, build them sequentially to avoid integration issues.

4. **Review AI code like you'd review junior developer code.** It's usually correct, sometimes creative, occasionally wrong. Don't rubber-stamp PRs.

5. **Make everything idempotent.** Migration scripts, seed data, configuration — if it can be run twice safely, it should be.

6. **Invest in CI/CD early.** Automated tests and linting catch AI-generated issues before they reach production.

## What's Next

The website is live, but the roadmap continues:

- **Content Management System** (Spec 007) — an admin interface for managing blog posts, skills, and jobs without writing migration scripts
- **Auth0 Integration** — secure the admin interface with proper authentication
- **AI Chat with MCP Tools** — an AI-powered chat feature that can query the site's content using Model Context Protocol

The spec-driven approach means these future features already have specifications waiting to be implemented. When I'm ready to build them, the AI agents will have all the context they need.

## Final Thoughts

Rebuilding a personal website might seem like a small project, but it was the perfect testbed for AI-assisted development at scale. The combination of SpecKit for structured planning, Claude Code for intelligent implementation, and Conductor for parallel execution created a workflow that was genuinely faster than traditional development — without sacrificing code quality.

The future of software development isn't AI replacing developers. It's developers who know how to effectively direct AI agents outperforming those who don't. Specification-driven development is the bridge between human intent and AI execution.

---

*This is Part 5 of a 5-part series on rebuilding simonrowe.dev with AI coding agents. [Start from Part 1 →](/blogs)*`;

// ============================================================================
// Phase 5: Insert blog posts
// ============================================================================

print('');
print('--- Inserting blog posts ---');

const posts = [
  {
    title: 'From Zero to Specification: How I Used AI to Plan My Entire Website Rebuild',
    shortDescription: 'How SpecKit and spec-driven development helped plan 9 features for simonrowe.dev before writing a single line of code.',
    content: post1Content,
    featuredImageUrl: '/uploads/blog-rebuild-1-specification.png',
    createdDate: ISODate('2026-02-27T08:00:00Z'),
    tags: [tagRef('SpecKit'), tagRef('Spec-Driven Development'), tagRef('AI')],
    skills: []
  },
  {
    title: 'Building the Foundation: Infrastructure and First Features in a Weekend',
    shortDescription: 'Setting up a Spring Boot + React monorepo with MongoDB, building a profile homepage and blog system in two days using Claude Code and Conductor.',
    content: post2Content,
    featuredImageUrl: '/uploads/blog-rebuild-2-foundation.png',
    createdDate: ISODate('2026-02-27T09:00:00Z'),
    tags: [tagRef('Spring'), tagRef('React'), tagRef('MongoDB'), tagRef('Conductor'), tagRef('AI')],
    skills: [skillRef('Java 21'), skillRef('Spring Boot'), skillRef('React'), skillRef('MongoDB'), skillRef('Docker'), skillRef('Typescript')]
  },
  {
    title: 'Shipping Six Features in a Day: Parallel AI Agents with Conductor',
    shortDescription: "How Conductor's parallel workspace model enabled shipping skills, blog system, site search, and contact form features all in a single day.",
    content: post3Content,
    featuredImageUrl: '/uploads/blog-rebuild-3-parallel.png',
    createdDate: ISODate('2026-02-27T10:00:00Z'),
    tags: [tagRef('Conductor'), tagRef('AI Productivity'), tagRef('Parallel Development')],
    skills: [skillRef('React'), skillRef('Elastic Search'), skillRef('Java 21'), skillRef('Spring Boot'), skillRef('MongoDB')]
  },
  {
    title: 'Interactive Tours, Data Migration, and the Finishing Touches',
    shortDescription: 'Migrating 18 blog posts from Strapi, building an interactive tour system, and solving real-world integration challenges with AI agents.',
    content: post4Content,
    featuredImageUrl: '/uploads/blog-rebuild-4-migration.png',
    createdDate: ISODate('2026-02-27T11:00:00Z'),
    tags: [tagRef('Data Migration'), tagRef('AI')],
    skills: [skillRef('MongoDB'), skillRef('Docker'), skillRef('React'), skillRef('Javascript')]
  },
  {
    title: "Lessons Learned: What Worked, What Didn't, and What's Next",
    shortDescription: 'Honest retrospective on rebuilding a personal website with AI coding agents — the wins, the struggles, and the roadmap ahead.',
    content: post5Content,
    featuredImageUrl: '/uploads/blog-rebuild-5-lessons.png',
    createdDate: ISODate('2026-02-27T12:00:00Z'),
    tags: [tagRef('SpecKit'), tagRef('Conductor'), tagRef('Retrospective'), tagRef('AI Productivity')],
    skills: [skillRef('Java 21'), skillRef('Spring Boot'), skillRef('React'), skillRef('MongoDB'), skillRef('Elastic Search'), skillRef('Docker')]
  }
];

for (const post of posts) {
  db.blogs.insertOne({
    _id: ObjectId(),
    title: post.title,
    shortDescription: post.shortDescription,
    content: post.content,
    published: true,
    featuredImageUrl: post.featuredImageUrl,
    createdDate: post.createdDate,
    updatedDate: post.createdDate,
    tags: post.tags,
    skills: post.skills
  });
  print('  Inserted: ' + post.title);
}

// ============================================================================
// Summary
// ============================================================================

print('');
print('=== Migration Summary ===');
print('Total published blogs: ' + db.blogs.countDocuments({ published: true }));
print('Total tags: ' + db.tags.countDocuments());
print('New blog posts: ' + posts.length);
print('=== Migration complete ===');
