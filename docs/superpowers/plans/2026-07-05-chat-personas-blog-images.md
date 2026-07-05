# Chat Personas, Source Widgets, and Editorial Blog Digests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the chat reliable for a technical hiring manager persona, render image-capable blog/news/event source widgets, and generate personal editorial digest posts with source-specific hero image prompts.

**Architecture:** Keep the existing STOMP chat stream and tool-driven widget model. Add typed news/event widget payloads, enrich blog widget payloads with optional images, and extract small backend helpers for digest metadata and image prompt context so behavior is testable without calling external services.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring AI, Embabel, MongoDB repositories, React 19, TypeScript 5.7, Vite 6, Vitest, Testing Library, JUnit 5, Mockito.

---

## File Structure

### Backend

- Modify `backend/src/main/java/com/simonrowe/chat/BlogWidgetPayload.java`
  - Add optional `imageUrl` to blog widget posts.
- Create `backend/src/main/java/com/simonrowe/chat/NewsWidgetPayload.java`
  - Typed payload for aggregated article cards.
- Create `backend/src/main/java/com/simonrowe/chat/EventWidgetPayload.java`
  - Typed payload for event cards with optional image support.
- Modify `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
  - Publish `news` and `events` widgets.
  - Include optional image data in blog/news/event payloads.
  - Keep existing return values for LLM context.
- Modify `backend/src/main/java/com/simonrowe/chat/ChatConfig.java`
  - Extend system prompt guidance to mention news/events widgets and one-response behavior.
- Create `backend/src/main/java/com/simonrowe/agents/DigestMetadata.java`
  - Holds generated title and short description.
- Create `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java`
  - Generates/falls back digest title and short description from source material.
- Modify `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java`
  - Use `DigestMetadataGenerator`.
  - Pass richer image context into `BlogImageGenerationService`.
- Modify `backend/src/main/java/com/simonrowe/media/BlogImageGenerationService.java`
  - Add an overload accepting image context while preserving the existing API.
  - Broaden prompt styles away from generic abstract tech art.
- Modify tests:
  - `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`
  - `backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java`
  - `backend/src/test/java/com/simonrowe/media/BlogImageGenerationServiceTest.java`

### Frontend

- Modify `frontend/src/components/chat/chatTypes.ts`
  - Add optional `imageUrl` to blog posts.
  - Add `NewsWidgetPayload` and `EventWidgetPayload`.
- Create `frontend/src/components/chat/widgets/chatWidgetImages.ts`
  - Resolves local upload and remote image URLs for widget components.
- Modify `frontend/src/components/chat/widgets/BlogListWidget.tsx`
  - Render blog image when present.
- Create `frontend/src/components/chat/widgets/NewsWidget.tsx`
  - Render news/article cards with optional images.
- Create `frontend/src/components/chat/widgets/EventsWidget.tsx`
  - Render event cards with date/venue/location and optional images.
- Modify `frontend/src/components/chat/widgets/ChatWidgetRegistry.tsx`
  - Register `news` and `events`.
- Modify tests:
  - `frontend/src/components/chat/widgets/ChatWidgets.test.tsx`
  - `frontend/src/components/chat/widgets/ChatWidgetRegistry.test.tsx`
  - `frontend/src/components/chat/ChatPanel.test.tsx`

---

### Task 1: Frontend Chat Lifecycle Guardrails

**Files:**
- Modify: `frontend/src/components/chat/ChatPanel.test.tsx`
- Modify only if a test fails: `frontend/src/components/chat/ChatPanel.tsx`

- [ ] **Step 1: Write failing lifecycle tests**

Update `frontend/src/components/chat/ChatPanel.test.tsx` to import the mocked service and add these tests below the existing stream-rendering test:

```tsx
import * as chatService from '../../services/chatService'
```

```tsx
  it('does not send a message or render an assistant response on initial mount without an initial query', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    expect(chatService.connect).toHaveBeenCalledTimes(1)
    expect(chatService.sendMessage).not.toHaveBeenCalled()
    expect(screen.getByText("Hi, I'm Simon's AI assistant")).toBeInTheDocument()
    expect(screen.queryByText('Assistant')).not.toBeInTheDocument()
    expect(screen.queryByText(/Used \d+ tools?/)).not.toBeInTheDocument()
  })

  it('sends an initial query once after connecting', () => {
    vi.useFakeTimers()

    render(<ChatPanel onClose={() => {}} initialQuery="Show me recent AI work" visible />)

    expect(screen.getByText('Show me recent AI work')).toBeInTheDocument()
    expect(chatService.sendMessage).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(50)
    })

    expect(chatService.sendMessage).toHaveBeenCalledTimes(1)
    expect(chatService.sendMessage).toHaveBeenCalledWith({
      sessionId: expect.any(String),
      message: 'Show me recent AI work',
    })

    vi.useRealTimers()
  })

  it('ignores duplicate stream end events for the same assistant response', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    act(() => {
      chatMock.onMessage?.(response({ type: 'STREAM_START' }))
      chatMock.onMessage?.(response({ type: 'STREAM_CHUNK', content: 'One answer.' }))
      chatMock.onMessage?.(response({ type: 'STREAM_END' }))
      chatMock.onMessage?.(response({ type: 'STREAM_END' }))
    })

    expect(screen.getAllByText('One answer.')).toHaveLength(1)
  })

  it('does not replay an old response after clearing chat', () => {
    render(<ChatPanel onClose={() => {}} visible />)

    act(() => {
      chatMock.onMessage?.(response({ type: 'STREAM_START' }))
      chatMock.onMessage?.(response({ type: 'STREAM_CHUNK', content: 'Old response.' }))
      chatMock.onMessage?.(response({ type: 'STREAM_END' }))
    })

    expect(screen.getByText('Old response.')).toBeInTheDocument()

    screen.getByRole('button', { name: /clear chat/i }).click()

    expect(screen.queryByText('Old response.')).not.toBeInTheDocument()
    expect(chatService.sendMessage).not.toHaveBeenCalled()
  })
```

- [ ] **Step 2: Run the new tests and verify the current behavior**

Run:

```bash
cd frontend && npm test -- src/components/chat/ChatPanel.test.tsx
```

Expected: the new tests either pass immediately or reveal the specific lifecycle bug. If they pass, do not change `ChatPanel.tsx` in this task.

- [ ] **Step 3: If the clear-chat test fails, patch `ChatPanel.tsx`**

Use this exact implementation for `handleClearChat` so clearing never reuses the old session callback and never sends a message:

```tsx
  const handleClearChat = () => {
    chatService.disconnect()
    setMessages([])
    setActiveAssistant(null)
    setConnected(false)
    streamFinalized.current = false
    cancelledRef.current = false
    clearTimeout(streamTimeoutRef.current)

    const newSessionId = crypto.randomUUID()
    sessionIdRef.current = newSessionId

    chatService.connect(
      newSessionId,
      onMessage,
      () => {
        if (!cancelledRef.current) {
          setConnected(true)
        }
      },
      () => {
        if (!cancelledRef.current) {
          setConnected(false)
        }
      }
    )
  }
```

- [ ] **Step 4: Re-run the test**

Run:

```bash
cd frontend && npm test -- src/components/chat/ChatPanel.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/chat/ChatPanel.test.tsx frontend/src/components/chat/ChatPanel.tsx
git commit -m "[FEAT] Guard chat response lifecycle"
```

If `ChatPanel.tsx` was not modified, omit it from `git add`.

---

### Task 2: Frontend Image-Capable Blog, News, and Event Widgets

**Files:**
- Modify: `frontend/src/components/chat/chatTypes.ts`
- Create: `frontend/src/components/chat/widgets/chatWidgetImages.ts`
- Modify: `frontend/src/components/chat/widgets/BlogListWidget.tsx`
- Create: `frontend/src/components/chat/widgets/NewsWidget.tsx`
- Create: `frontend/src/components/chat/widgets/EventsWidget.tsx`
- Modify: `frontend/src/components/chat/widgets/ChatWidgetRegistry.tsx`
- Modify: `frontend/src/components/chat/widgets/ChatWidgets.test.tsx`
- Modify: `frontend/src/components/chat/widgets/ChatWidgetRegistry.test.tsx`

- [ ] **Step 1: Write widget tests**

Add these imports to `frontend/src/components/chat/widgets/ChatWidgets.test.tsx`:

```tsx
import { NewsWidget } from './NewsWidget'
import { EventsWidget } from './EventsWidget'
```

Add these tests inside `describe('chat widgets', () => { ... })`:

```tsx
  it('renders blog image when present', () => {
    render(<BlogListWidget payload={{
      posts: [{
        title: 'Streaming chat',
        summary: 'Why visible progress matters',
        imageUrl: '/uploads/blog-1/small.webp',
        publishedDate: '2026-05-01T00:00:00Z',
        tags: ['AI'],
        url: '/blogs/streaming-chat',
      }],
    }} />)

    const image = screen.getByRole('img', { name: 'Streaming chat' })
    expect(image).toHaveAttribute('src', expect.stringContaining('/uploads/blog-1/small.webp'))
  })

  it('does not render an empty blog image slot when image is missing', () => {
    const { container } = render(<BlogListWidget payload={{
      posts: [{
        title: 'Streaming chat',
        summary: 'Why visible progress matters',
        publishedDate: '2026-05-01T00:00:00Z',
        tags: ['AI'],
        url: '/blogs/streaming-chat',
      }],
    }} />)

    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(container.querySelector('.chat-widget__media')).toBeNull()
  })

  it('renders news cards with optional images and source links', () => {
    render(<NewsWidget payload={{
      articles: [{
        title: 'Spring AI adds new advisor APIs',
        summary: 'Advisor APIs improve RAG composition.',
        sourceName: 'Spring Blog',
        originalUrl: 'https://spring.io/blog/advisors',
        publishedDate: '2026-07-01T09:00:00Z',
        imageUrl: 'https://example.com/spring.png',
      }],
    }} />)

    expect(screen.getByText('Spring AI adds new advisor APIs')).toBeInTheDocument()
    expect(screen.getByText('Spring Blog')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Spring AI adds new advisor APIs' }))
      .toHaveAttribute('src', 'https://example.com/spring.png')
    expect(screen.getByRole('link', { name: /read source/i })).toHaveAttribute(
      'href',
      'https://spring.io/blog/advisors',
    )
  })

  it('renders news cards without image slots when image is missing', () => {
    const { container } = render(<NewsWidget payload={{
      articles: [{
        title: 'No Image Article',
        summary: 'Text-only card.',
        sourceName: 'InfoQ',
        originalUrl: 'https://infoq.com/no-image',
      }],
    }} />)

    expect(screen.getByText('No Image Article')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(container.querySelector('.chat-widget__media')).toBeNull()
  })

  it('renders event cards with date venue and external link', () => {
    render(<EventsWidget payload={{
      events: [{
        title: 'London Java Meetup',
        summary: 'Talks on production Java.',
        sourceName: 'Luma',
        originalUrl: 'https://lu.ma/java',
        eventDate: '2026-07-20T18:30:00Z',
        venue: 'CodeNode',
        location: 'London',
      }],
    }} />)

    expect(screen.getByText('London Java Meetup')).toBeInTheDocument()
    expect(screen.getByText('Luma')).toBeInTheDocument()
    expect(screen.getByText(/CodeNode, London/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /view event/i })).toHaveAttribute(
      'href',
      'https://lu.ma/java',
    )
  })
```

Add these tests to `frontend/src/components/chat/widgets/ChatWidgetRegistry.test.tsx`:

```tsx
  it('renders a known news widget', () => {
    render(<ChatWidget widgetKind="news" payload={{
      articles: [{
        title: 'Spring AI News',
        sourceName: 'Spring Blog',
        originalUrl: 'https://spring.io/blog/news',
      }],
    }} />)

    expect(screen.getByText('Spring AI News')).toBeInTheDocument()
    expect(screen.getByText('Spring Blog')).toBeInTheDocument()
  })

  it('renders a known events widget', () => {
    render(<ChatWidget widgetKind="events" payload={{
      events: [{
        title: 'AI Engineering Meetup',
        sourceName: 'Luma',
        originalUrl: 'https://lu.ma/ai',
        eventDate: '2026-07-20T18:30:00Z',
      }],
    }} />)

    expect(screen.getByText('AI Engineering Meetup')).toBeInTheDocument()
    expect(screen.getByText('Luma')).toBeInTheDocument()
  })
```

- [ ] **Step 2: Run widget tests and verify failure**

Run:

```bash
cd frontend && npm test -- src/components/chat/widgets/ChatWidgets.test.tsx src/components/chat/widgets/ChatWidgetRegistry.test.tsx
```

Expected: FAIL because `NewsWidget`, `EventsWidget`, and the new type fields do not exist.

- [ ] **Step 3: Update chat widget types**

Replace `frontend/src/components/chat/chatTypes.ts` with:

```ts
export type WidgetKind = 'skills' | 'employment' | 'code' | 'blogs' | 'news' | 'events'

export interface SkillWidgetPayload {
  groups: Array<{
    name: string
    skills: Array<{ name: string; rating?: number | null }>
  }>
}

export interface EmploymentWidgetPayload {
  jobs: Array<{
    company: string
    title: string
    start?: string | null
    end?: string | null
    summary?: string | null
    skills?: string[]
  }>
}

export interface CodeWidgetPayload {
  examples: Array<{
    id?: string | null
    title: string
    description?: string | null
    language?: string | null
    code?: string | null
    skills?: string[]
  }>
}

export interface BlogWidgetPayload {
  posts: Array<{
    id?: string | null
    title: string
    summary?: string | null
    tags?: string[]
    publishedDate?: string | null
    url?: string | null
    imageUrl?: string | null
  }>
}

export interface NewsWidgetPayload {
  articles: Array<{
    id?: string | null
    title: string
    summary?: string | null
    sourceName?: string | null
    originalUrl?: string | null
    publishedDate?: string | null
    imageUrl?: string | null
  }>
}

export interface EventWidgetPayload {
  events: Array<{
    id?: string | null
    title: string
    summary?: string | null
    sourceName?: string | null
    originalUrl?: string | null
    eventDate?: string | null
    eventEndDate?: string | null
    venue?: string | null
    location?: string | null
    imageUrl?: string | null
  }>
}

export type ChatBlock =
  | { kind: 'text'; content: string }
  | { kind: 'tool'; label: string; status: 'running' | 'done' }
  | { kind: 'widget'; widgetKind: string; payload: unknown }

export interface ChatMessageModel {
  role: 'user' | 'assistant'
  content?: string
  blocks?: ChatBlock[]
  timestamp: string
  finalized?: boolean
}
```

- [ ] **Step 4: Add image URL resolver**

Create `frontend/src/components/chat/widgets/chatWidgetImages.ts`:

```ts
import { API_BASE_URL } from '../../../config/api'

export function resolveChatWidgetImageUrl(url?: string | null): string | undefined {
  if (!url) return undefined
  if (url.startsWith('/uploads/')) return `${API_BASE_URL}${url}`
  if (url.startsWith('http://') || url.startsWith('https://')) return url
  return undefined
}
```

- [ ] **Step 5: Update blog widget image rendering**

Replace `frontend/src/components/chat/widgets/BlogListWidget.tsx` with:

```tsx
import type { BlogWidgetPayload } from '../chatTypes'
import { resolveChatWidgetImageUrl } from './chatWidgetImages'

interface BlogListWidgetProps {
  payload: BlogWidgetPayload
}

export function BlogListWidget({ payload }: BlogListWidgetProps) {
  if (!payload.posts?.length) return null

  return (
    <div className="chat-widget chat-widget--blogs">
      {payload.posts.map(post => {
        const imageUrl = resolveChatWidgetImageUrl(post.imageUrl)
        return (
          <article className="chat-widget__item" key={post.id ?? post.url ?? post.title}>
            {imageUrl && (
              <div className="chat-widget__media">
                <img src={imageUrl} alt={post.title} className="chat-widget__image" />
              </div>
            )}
            <div className="chat-widget__item-head">
              <h4 className="chat-widget__title">{post.title}</h4>
              {post.publishedDate && (
                <span className="chat-widget__meta">{formatDate(post.publishedDate)}</span>
              )}
            </div>
            {post.summary && <p className="chat-widget__summary">{post.summary}</p>}
            {!!post.tags?.length && (
              <div className="chat-widget__chips">
                {post.tags.map(tag => (
                  <span className="chat-widget__chip" key={tag}>{tag}</span>
                ))}
              </div>
            )}
            {post.url && (
              <a className="chat-widget__link" href={post.url}>
                Read post
              </a>
            )}
          </article>
        )
      })}
    </div>
  )
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
```

- [ ] **Step 6: Add news widget**

Create `frontend/src/components/chat/widgets/NewsWidget.tsx`:

```tsx
import type { NewsWidgetPayload } from '../chatTypes'
import { resolveChatWidgetImageUrl } from './chatWidgetImages'

interface NewsWidgetProps {
  payload: NewsWidgetPayload
}

export function NewsWidget({ payload }: NewsWidgetProps) {
  if (!payload.articles?.length) return null

  return (
    <div className="chat-widget chat-widget--news">
      {payload.articles.map(article => {
        const imageUrl = resolveChatWidgetImageUrl(article.imageUrl)
        return (
          <article className="chat-widget__item" key={article.id ?? article.originalUrl ?? article.title}>
            {imageUrl && (
              <div className="chat-widget__media">
                <img src={imageUrl} alt={article.title} className="chat-widget__image" />
              </div>
            )}
            <div className="chat-widget__item-head">
              <h4 className="chat-widget__title">{article.title}</h4>
              {article.publishedDate && (
                <span className="chat-widget__meta">{formatDate(article.publishedDate)}</span>
              )}
            </div>
            {article.sourceName && (
              <div className="chat-widget__source">{article.sourceName}</div>
            )}
            {article.summary && <p className="chat-widget__summary">{article.summary}</p>}
            {article.originalUrl && (
              <a
                className="chat-widget__link"
                href={article.originalUrl}
                rel="noopener noreferrer"
                target="_blank"
              >
                Read source
              </a>
            )}
          </article>
        )
      })}
    </div>
  )
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}
```

- [ ] **Step 7: Add events widget**

Create `frontend/src/components/chat/widgets/EventsWidget.tsx`:

```tsx
import { Calendar, MapPin } from 'lucide-react'
import type { EventWidgetPayload } from '../chatTypes'
import { resolveChatWidgetImageUrl } from './chatWidgetImages'

interface EventsWidgetProps {
  payload: EventWidgetPayload
}

export function EventsWidget({ payload }: EventsWidgetProps) {
  if (!payload.events?.length) return null

  return (
    <div className="chat-widget chat-widget--events">
      {payload.events.map(event => {
        const imageUrl = resolveChatWidgetImageUrl(event.imageUrl)
        return (
          <article className="chat-widget__item" key={event.id ?? event.originalUrl ?? event.title}>
            {imageUrl && (
              <div className="chat-widget__media">
                <img src={imageUrl} alt={event.title} className="chat-widget__image" />
              </div>
            )}
            <div className="chat-widget__item-head">
              <h4 className="chat-widget__title">{event.title}</h4>
              {event.sourceName && <span className="chat-widget__meta">{event.sourceName}</span>}
            </div>
            {event.eventDate && (
              <div className="chat-widget__detail">
                <Calendar size={14} />
                <span>{formatDateTime(event.eventDate)}</span>
              </div>
            )}
            {(event.venue || event.location) && (
              <div className="chat-widget__detail">
                <MapPin size={14} />
                <span>{[event.venue, event.location].filter(Boolean).join(', ')}</span>
              </div>
            )}
            {event.summary && <p className="chat-widget__summary">{event.summary}</p>}
            {event.originalUrl && (
              <a
                className="chat-widget__link"
                href={event.originalUrl}
                rel="noopener noreferrer"
                target="_blank"
              >
                View event
              </a>
            )}
          </article>
        )
      })}
    </div>
  )
}

function formatDateTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
```

- [ ] **Step 8: Register new widgets**

Replace `frontend/src/components/chat/widgets/ChatWidgetRegistry.tsx` with:

```tsx
import { BlogListWidget } from './BlogListWidget'
import { CodeExampleWidget } from './CodeExampleWidget'
import { EmploymentWidget } from './EmploymentWidget'
import { EventsWidget } from './EventsWidget'
import { NewsWidget } from './NewsWidget'
import { SkillsWidget } from './SkillsWidget'
import type {
  BlogWidgetPayload,
  CodeWidgetPayload,
  EmploymentWidgetPayload,
  EventWidgetPayload,
  NewsWidgetPayload,
  SkillWidgetPayload,
} from '../chatTypes'

interface ChatWidgetProps {
  widgetKind: string
  payload: unknown
}

export function ChatWidget({ widgetKind, payload }: ChatWidgetProps) {
  if (widgetKind === 'skills') {
    return <SkillsWidget payload={payload as SkillWidgetPayload} />
  }
  if (widgetKind === 'employment') {
    return <EmploymentWidget payload={payload as EmploymentWidgetPayload} />
  }
  if (widgetKind === 'code') {
    return <CodeExampleWidget payload={payload as CodeWidgetPayload} />
  }
  if (widgetKind === 'blogs') {
    return <BlogListWidget payload={payload as BlogWidgetPayload} />
  }
  if (widgetKind === 'news') {
    return <NewsWidget payload={payload as NewsWidgetPayload} />
  }
  if (widgetKind === 'events') {
    return <EventsWidget payload={payload as EventWidgetPayload} />
  }
  return null
}
```

- [ ] **Step 9: Add minimal CSS for media and detail rows**

Append to `frontend/src/styles.css` near existing `.chat-widget` styles:

```css
.chat-widget__media {
  margin-bottom: 0.75rem;
  overflow: hidden;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.06);
}

.chat-widget__image {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: cover;
}

.chat-widget__source {
  margin-bottom: 0.35rem;
  font-size: 0.78rem;
  font-weight: 700;
  color: var(--color-accent, #2563eb);
}

.chat-widget__detail {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  margin: 0.35rem 0;
  font-size: 0.85rem;
  color: var(--color-text-muted, #64748b);
}
```

- [ ] **Step 10: Re-run widget tests**

Run:

```bash
cd frontend && npm test -- src/components/chat/widgets/ChatWidgets.test.tsx src/components/chat/widgets/ChatWidgetRegistry.test.tsx
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/components/chat/chatTypes.ts frontend/src/components/chat/widgets frontend/src/styles.css
git commit -m "[FEAT] Add image capable chat source widgets"
```

---

### Task 3: Backend News/Event Widget Payloads

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/BlogWidgetPayload.java`
- Create: `backend/src/main/java/com/simonrowe/chat/NewsWidgetPayload.java`
- Create: `backend/src/main/java/com/simonrowe/chat/EventWidgetPayload.java`
- Modify: `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`
- Modify: `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`

- [ ] **Step 1: Write backend widget tests**

Add imports to `backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java`:

```java
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.chat.BlogWidgetPayload;
import com.simonrowe.chat.EventWidgetPayload;
import com.simonrowe.chat.NewsWidgetPayload;
import java.time.Instant;
import org.mockito.ArgumentCaptor;
```

Add these tests near the existing news/event tests:

```java
  @Test
  void searchNewsWithoutQueryPublishesNewsWidgetWhenArticlesExist() {
    final ToolContext context = new ToolContext(Map.of("sessionId", "sess-news"));
    final AggregatedArticle article = new AggregatedArticle(
        "art-1", "Spring AI Advisors", "Spring Blog",
        "https://spring.io", "https://spring.io/blog/advisors",
        "Advisor APIs improve RAG composition.", "Full content", "Simon",
        Instant.parse("2026-07-01T09:00:00Z"),
        Instant.parse("2026-07-01T10:00:00Z"),
        true, "/uploads/art-1/small.webp");
    given(articleRepository.findByVisibleTrueOrderByPublishedDateDesc(
        org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
        .willReturn(new org.springframework.data.domain.PageImpl<>(List.of(article)));

    profileMcpTools.searchNews(null, context);

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    InOrder order = inOrder(streamPublisher);
    order.verify(streamPublisher).toolStart("sess-news", "Searching tech news");
    order.verify(streamPublisher).widget(eq("sess-news"), eq("news"), payload.capture());
    order.verify(streamPublisher).toolEnd("sess-news", "Searching tech news");

    NewsWidgetPayload news = (NewsWidgetPayload) payload.getValue();
    assertThat(news.articles()).hasSize(1);
    assertThat(news.articles().getFirst().title()).isEqualTo("Spring AI Advisors");
    assertThat(news.articles().getFirst().imageUrl()).isEqualTo("/uploads/art-1/small.webp");
  }

  @Test
  void searchNewsSkipsWidgetWhenNoArticlesExist() {
    final ToolContext context = new ToolContext(Map.of("sessionId", "sess-news-empty"));
    given(articleRepository.findByVisibleTrueOrderByPublishedDateDesc(
        org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
        .willReturn(org.springframework.data.domain.Page.empty());

    profileMcpTools.searchNews(null, context);

    verify(streamPublisher).toolStart("sess-news-empty", "Searching tech news");
    verify(streamPublisher, never()).widget(eq("sess-news-empty"), eq("news"), any());
    verify(streamPublisher).toolEnd("sess-news-empty", "Searching tech news");
  }

  @Test
  void getUpcomingEventsPublishesEventsWidgetWhenEventsExist() {
    final ToolContext context = new ToolContext(Map.of("sessionId", "sess-events"));
    final AggregatedEvent event = new AggregatedEvent(
        "evt-1", "London Java Meetup", "Luma",
        "https://lu.ma/java", "Production Java talks.", "Detailed description",
        Instant.parse("2026-07-20T18:30:00Z"),
        Instant.parse("2026-07-20T20:30:00Z"),
        "CodeNode", "London",
        Instant.parse("2026-07-01T10:00:00Z"), true);
    given(eventRepository.findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(
        org.mockito.ArgumentMatchers.any())).willReturn(List.of(event));

    profileMcpTools.getUpcomingEvents(null, context);

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    InOrder order = inOrder(streamPublisher);
    order.verify(streamPublisher).toolStart("sess-events", "Finding upcoming events");
    order.verify(streamPublisher).widget(eq("sess-events"), eq("events"), payload.capture());
    order.verify(streamPublisher).toolEnd("sess-events", "Finding upcoming events");

    EventWidgetPayload events = (EventWidgetPayload) payload.getValue();
    assertThat(events.events()).hasSize(1);
    assertThat(events.events().getFirst().title()).isEqualTo("London Java Meetup");
    assertThat(events.events().getFirst().venue()).isEqualTo("CodeNode");
  }

  @Test
  void getRecentBlogsPublishesImageUrlInBlogWidgetPayload() {
    final ToolContext context = new ToolContext(Map.of("sessionId", "sess-blog-image"));
    given(blogService.getLatest(10)).willReturn(List.of(sampleBlogSummaryResponse()));

    profileMcpTools.getRecentBlogs(context);

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(streamPublisher).widget(eq("sess-blog-image"), eq("blogs"), payload.capture());

    BlogWidgetPayload blogs = (BlogWidgetPayload) payload.getValue();
    assertThat(blogs.posts()).hasSize(1);
    assertThat(blogs.posts().getFirst().imageUrl()).isEqualTo("/images/blog.jpg");
  }
```

If `sampleBlogSummaryResponse()` currently returns a null image, update that helper to use an image path. The exact helper body must be:

```java
  private static BlogSummaryResponse sampleBlogSummaryResponse() {
    return new BlogSummaryResponse(
        "blog-1",
        "Streaming chat",
        "Why visible progress matters",
        "/images/blog.jpg",
        java.time.Instant.parse("2026-05-01T00:00:00Z"),
        List.of(),
        List.of(),
        "/blogs/blog-1");
  }
```

- [ ] **Step 2: Run backend MCP tests and verify failure**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.mcp.ProfileMcpToolsTest
```

Expected: FAIL because payload records and overloads do not exist yet.

- [ ] **Step 3: Update blog payload record**

Replace `backend/src/main/java/com/simonrowe/chat/BlogWidgetPayload.java` with:

```java
package com.simonrowe.chat;

import java.util.List;

public record BlogWidgetPayload(List<Post> posts) {

  public record Post(
      String id,
      String title,
      String summary,
      List<String> tags,
      String publishedDate,
      String url,
      String imageUrl
  ) {
  }
}
```

- [ ] **Step 4: Add news payload record**

Create `backend/src/main/java/com/simonrowe/chat/NewsWidgetPayload.java`:

```java
package com.simonrowe.chat;

import java.util.List;

public record NewsWidgetPayload(List<Article> articles) {

  public record Article(
      String id,
      String title,
      String summary,
      String sourceName,
      String originalUrl,
      String publishedDate,
      String imageUrl
  ) {
  }
}
```

- [ ] **Step 5: Add event payload record**

Create `backend/src/main/java/com/simonrowe/chat/EventWidgetPayload.java`:

```java
package com.simonrowe.chat;

import java.util.List;

public record EventWidgetPayload(List<Event> events) {

  public record Event(
      String id,
      String title,
      String summary,
      String sourceName,
      String originalUrl,
      String eventDate,
      String eventEndDate,
      String venue,
      String location,
      String imageUrl
  ) {
  }
}
```

- [ ] **Step 6: Update `ProfileMcpTools` imports and labels**

In `backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java`, add imports:

```java
import com.simonrowe.chat.EventWidgetPayload;
import com.simonrowe.chat.NewsWidgetPayload;
```

Add labels near existing label constants:

```java
  private static final String NEWS_LABEL = "Searching tech news";
  private static final String EVENTS_LABEL = "Finding upcoming events";
```

- [ ] **Step 7: Add context overloads for news and events**

Replace the existing `searchNews` method with this overload pair:

```java
  public Object searchNews(final String query) {
    return searchNews(query, null);
  }

  @WithSpan
  @Tool(description = "Search aggregated tech news articles from external sources like "
      + "AI Native Dev, Rundown AI, and Spring Blog. Returns recent articles with "
      + "AI-generated summaries and source attribution. Use this when asked about "
      + "recent tech news, industry trends, or what's happening in the tech world.")
  public Object searchNews(
      @ToolParam(description = "Search keywords to match against news article titles "
          + "and summaries. Pass null or empty for latest articles.")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, NEWS_LABEL);
    try {
      if (query != null && !query.isBlank()) {
        try {
          List<SearchResult> results = searchService.searchByType(query, "news");
          return results.stream()
              .map(r -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("title", r.name());
                map.put("url", r.url());
                return map;
              })
              .toList();
        } catch (SearchUnavailableException e) {
          return SEARCH_UNAVAILABLE;
        }
      }
      List<AggregatedArticle> articles = articleRepository
          .findByVisibleTrueOrderByPublishedDateDesc(
              org.springframework.data.domain.PageRequest.of(0, 10))
          .getContent();
      publishWidgetIfNotEmpty(sessionId, "news", toNewsPayload(articles));
      return articles.stream()
          .map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", a.title());
            map.put("summary", a.summary());
            map.put("sourceName", a.sourceName());
            map.put("originalUrl", a.originalUrl());
            map.put("publishedDate", a.publishedDate());
            map.put("imageUrl", a.imageUrl());
            return map;
          })
          .toList();
    } finally {
      publishToolEnd(sessionId, NEWS_LABEL);
    }
  }
```

Replace the existing `getUpcomingEvents` method with this overload pair:

```java
  public Object getUpcomingEvents(final String query) {
    return getUpcomingEvents(query, null);
  }

  @WithSpan
  @Tool(description = "Get upcoming tech community events like meetups and conferences. "
      + "Optionally filter by keyword. Returns events with dates, venues, and descriptions. "
      + "Use this when asked about upcoming events, meetups, or tech gatherings.")
  public Object getUpcomingEvents(
      @ToolParam(description = "Optional search keywords to filter events. "
          + "Pass null or empty for all upcoming events.")
      final String query,
      final ToolContext toolContext) {
    String sessionId = sessionId(toolContext);
    publishToolStart(sessionId, EVENTS_LABEL);
    try {
      if (query != null && !query.isBlank()) {
        try {
          return searchService.searchByType(query, "event");
        } catch (SearchUnavailableException e) {
          return SEARCH_UNAVAILABLE;
        }
      }
      List<AggregatedEvent> events = eventRepository
          .findByVisibleTrueAndEventDateAfterOrderByEventDateAsc(
              java.time.Instant.now());
      List<AggregatedEvent> limitedEvents = events.stream().limit(10).toList();
      publishWidgetIfNotEmpty(sessionId, "events", toEventPayload(limitedEvents));
      return limitedEvents.stream()
          .map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", e.title());
            map.put("summary", e.summary());
            map.put("sourceName", e.sourceName());
            map.put("originalUrl", e.originalUrl());
            map.put("eventDate", e.eventDate());
            map.put("venue", e.venue());
            map.put("location", e.location());
            return map;
          })
          .toList();
    } finally {
      publishToolEnd(sessionId, EVENTS_LABEL);
    }
  }
```

- [ ] **Step 8: Update empty payload detection**

Add these cases to `isEmptyPayload`:

```java
      case NewsWidgetPayload news -> news.articles().isEmpty();
      case EventWidgetPayload events -> events.events().isEmpty();
```

- [ ] **Step 9: Update blog payload mapping**

Update both blog payload builders to pass `imageUrl`:

```java
  private static BlogWidgetPayload toBlogSummaryPayload(
      final List<BlogSummaryResponse> blogs) {
    return new BlogWidgetPayload(blogs.stream()
        .map(blog -> new BlogWidgetPayload.Post(
            blog.id(),
            blog.title(),
            blog.shortDescription(),
            blog.tags() == null ? List.of() : blog.tags().stream()
                .map(tag -> tag.name())
                .toList(),
            blog.createdDate() == null ? null : blog.createdDate().toString(),
            blog.url(),
            blog.imageUrl()))
        .toList());
  }

  private static BlogWidgetPayload toBlogPayload(final List<BlogSearchResult> blogs) {
    return new BlogWidgetPayload(blogs.stream()
        .map(blog -> new BlogWidgetPayload.Post(
            null,
            blog.title(),
            blog.shortDescription(),
            List.of(),
            blog.publishedDate() == null ? null : blog.publishedDate().toString(),
            blog.url(),
            blog.image()))
        .toList());
  }
```

- [ ] **Step 10: Add news and event payload builders**

Add these methods near the blog payload builders:

```java
  private static NewsWidgetPayload toNewsPayload(final List<AggregatedArticle> articles) {
    return new NewsWidgetPayload(articles.stream()
        .map(article -> new NewsWidgetPayload.Article(
            article.id(),
            article.title(),
            article.summary(),
            article.sourceName(),
            article.originalUrl(),
            article.publishedDate() == null ? null : article.publishedDate().toString(),
            article.imageUrl()))
        .toList());
  }

  private static EventWidgetPayload toEventPayload(final List<AggregatedEvent> events) {
    return new EventWidgetPayload(events.stream()
        .map(event -> new EventWidgetPayload.Event(
            event.id(),
            event.title(),
            event.summary(),
            event.sourceName(),
            event.originalUrl(),
            event.eventDate() == null ? null : event.eventDate().toString(),
            event.eventEndDate() == null ? null : event.eventEndDate().toString(),
            event.venue(),
            event.location(),
            null))
        .toList());
  }
```

- [ ] **Step 11: Run backend MCP tests**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.mcp.ProfileMcpToolsTest
```

Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat backend/src/main/java/com/simonrowe/mcp/ProfileMcpTools.java backend/src/test/java/com/simonrowe/mcp/ProfileMcpToolsTest.java
git commit -m "[FEAT] Publish news event chat widgets"
```

---

### Task 4: Digest Metadata Strategy

**Files:**
- Create: `backend/src/main/java/com/simonrowe/agents/DigestMetadata.java`
- Create: `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java`
- Modify: `backend/src/main/java/com/simonrowe/agents/WeeklyDigestAgent.java`
- Modify: `backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java`

- [ ] **Step 1: Update weekly digest tests**

Modify constructor setup in `WeeklyDigestAgentTest` to add a generator field:

```java
  private DigestMetadataGenerator metadataGenerator;
```

In `setUp()`, initialize the generator and pass it to the agent:

```java
    metadataGenerator = new DigestMetadataGenerator(ai);

    agent = new WeeklyDigestAgent(
        blogRepository, tagRepository,
        articleRepository, ai, changePublisher,
        blogImageGenerationService, metadataGenerator);
```

Replace assertions in `generateDigest_createsDigestFromRecentArticles`:

```java
    assertThat(created.title())
        .doesNotStartWith("AI & Tech Roundup:");
    assertThat(created.title())
        .contains("Spring Boot 4 Released");
    assertThat(created.shortDescription())
        .contains("Spring Boot 4 Released");
```

Add this test:

```java
  @Test
  void generateDigest_passesSourceSpecificContextToImageGeneration() {
    Instant recentFetch =
        Instant.now().minus(1, ChronoUnit.DAYS);
    AggregatedArticle article = new AggregatedArticle(
        "art-image", "Agent frameworks mature", "AI Native Dev",
        "https://ainativedev.io",
        "https://ainativedev.io/agents",
        "Agent frameworks are becoming more practical.",
        "Full content", null,
        recentFetch, recentFetch, true, "/uploads/article/original.png");

    Blog savedDigest = new Blog(
        "blog-digest-image", "Digest",
        "Summary",
        "Content.", true, null,
        Instant.now(), Instant.now(),
        List.of(DIGEST_TAG), List.of());

    when(blogRepository
        .findByPublishedTrueOrderByCreatedDateDesc())
        .thenReturn(List.of());
    when(articleRepository
        .findByVisibleTrueOrderByPublishedDateDesc())
        .thenReturn(List.of(article));
    when(tagRepository.findByName("Weekly Digest"))
        .thenReturn(Optional.of(DIGEST_TAG));
    when(assistantMessage.getContent())
        .thenReturn("Content.");
    when(blogRepository.save(any()))
        .thenReturn(savedDigest);

    agent.generateDigest();

    verify(blogImageGenerationService).generateAndStore(
        org.mockito.ArgumentMatchers.contains("Agent frameworks mature"),
        org.mockito.ArgumentMatchers.contains("Agent frameworks mature"),
        org.mockito.ArgumentMatchers.contains("AI Native Dev"));
  }
```

- [ ] **Step 2: Run weekly digest tests and verify failure**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.agents.WeeklyDigestAgentTest
```

Expected: FAIL because `DigestMetadataGenerator` and the new image-generation overload do not exist.

- [ ] **Step 3: Create digest metadata record**

Create `backend/src/main/java/com/simonrowe/agents/DigestMetadata.java`:

```java
package com.simonrowe.agents;

public record DigestMetadata(String title, String shortDescription) {
}
```

- [ ] **Step 4: Create metadata generator**

Create `backend/src/main/java/com/simonrowe/agents/DigestMetadataGenerator.java`:

```java
package com.simonrowe.agents;

import com.embabel.agent.api.common.Ai;
import com.embabel.chat.UserMessage;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.blog.Blog;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DigestMetadataGenerator {

  private static final Logger LOG =
      LoggerFactory.getLogger(DigestMetadataGenerator.class);

  private static final String METADATA_PROMPT =
      "Generate metadata for a personal editorial digest post by Simon Rowe. "
          + "Return exactly two lines in this format:\n"
          + "Title: <human title, under 90 characters>\n"
          + "Description: <one sentence, under 160 characters>\n"
          + "Use first-person curated phrasing. Do not use the phrase "
          + "'AI & Tech Roundup'. Base the title on the source material.\n\n";

  private final Ai ai;

  public DigestMetadataGenerator(final Ai ai) {
    this.ai = ai;
  }

  public DigestMetadata generate(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles,
      final String activitySummary) {
    try {
      String content = ai.withLlm("gpt-4o-mini")
          .respond(List.of(new UserMessage(METADATA_PROMPT + activitySummary)))
          .getContent();
      DigestMetadata parsed = parse(content);
      if (isUsable(parsed)) {
        return parsed;
      }
    } catch (Exception ex) {
      LOG.warn("Failed to generate digest metadata: {}", ex.getMessage());
    }
    return fallback(recentBlogs, recentArticles);
  }

  private static DigestMetadata parse(final String content) {
    String title = null;
    String description = null;
    if (content != null) {
      for (String line : content.split("\\R")) {
        if (line.startsWith("Title:")) {
          title = line.substring("Title:".length()).trim();
        } else if (line.startsWith("Description:")) {
          description = line.substring("Description:".length()).trim();
        }
      }
    }
    return new DigestMetadata(title, description);
  }

  private static boolean isUsable(final DigestMetadata metadata) {
    return metadata.title() != null
        && !metadata.title().isBlank()
        && !metadata.title().startsWith("AI & Tech Roundup")
        && metadata.shortDescription() != null
        && !metadata.shortDescription().isBlank();
  }

  private static DigestMetadata fallback(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles) {
    String lead = recentArticles.stream()
        .findFirst()
        .map(AggregatedArticle::title)
        .or(() -> recentBlogs.stream().findFirst().map(Blog::title))
        .orElse("AI and backend engineering");
    String title = "What caught my eye: " + lead;
    if (title.length() > 90) {
      title = title.substring(0, 87).trim() + "...";
    }
    String description = "A few practical notes on " + lead + " and related engineering signals.";
    if (description.length() > 160) {
      description = description.substring(0, 157).trim() + "...";
    }
    return new DigestMetadata(title, description);
  }
}
```

- [ ] **Step 5: Update `WeeklyDigestAgent` constructor and fields**

Add field:

```java
  private final DigestMetadataGenerator metadataGenerator;
```

Update constructor:

```java
  public WeeklyDigestAgent(
      final BlogRepository blogRepository,
      final TagRepository tagRepository,
      final AggregatedArticleRepository articleRepository,
      final Ai ai,
      final ContentChangePublisher changePublisher,
      final BlogImageGenerationService blogImageGenerationService,
      final DigestMetadataGenerator metadataGenerator) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.articleRepository = articleRepository;
    this.ai = ai;
    this.changePublisher = changePublisher;
    this.blogImageGenerationService = blogImageGenerationService;
    this.metadataGenerator = metadataGenerator;
  }
```

- [ ] **Step 6: Replace fixed title/description in `generateDigest`**

Remove local date title construction and replace it with:

```java
    DigestMetadata metadata = metadataGenerator.generate(
        recentBlogs, recentArticles, activitySummary);
    String imageContext = buildImageContext(recentBlogs, recentArticles);

    String featuredImageUrl =
        blogImageGenerationService.generateAndStore(
            metadata.title(), metadata.shortDescription(), imageContext);

    Instant createdAt = Instant.now();
    Blog digest = new Blog(
        null, metadata.title(),
        metadata.shortDescription(),
        digestContent, true, featuredImageUrl,
        createdAt, createdAt,
        List.of(digestTag), List.<Skill>of());
```

Remove now-unused imports:

```java
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
```

Remove the constant:

```java
  private static final String DIGEST_SHORT_DESCRIPTION =
      "Latest roundup of site activity and tech news";
```

- [ ] **Step 7: Add image context builder**

Add this method to `WeeklyDigestAgent`:

```java
  private String buildImageContext(
      final List<Blog> recentBlogs,
      final List<AggregatedArticle> recentArticles) {
    StringBuilder sb = new StringBuilder();
    if (!recentBlogs.isEmpty()) {
      sb.append("Recent Simon posts: ");
      recentBlogs.stream().limit(5)
          .forEach(blog -> sb.append(blog.title())
              .append(" - ")
              .append(blog.shortDescription())
              .append("; "));
    }
    if (!recentArticles.isEmpty()) {
      sb.append("External sources: ");
      recentArticles.stream().limit(8)
          .forEach(article -> sb.append(article.title())
              .append(" from ")
              .append(article.sourceName())
              .append(" - ")
              .append(article.summary())
              .append("; "));
    }
    return sb.toString();
  }
```

- [ ] **Step 8: Re-run weekly digest tests**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.agents.WeeklyDigestAgentTest
```

Expected: FAIL with a missing `BlogImageGenerationService.generateAndStore(String, String, String)` method until Task 5 is complete.

- [ ] **Step 9: Commit after Task 5 passes**

Do not commit this task until Task 5 provides the image-generation overload and `WeeklyDigestAgentTest` passes.

---

### Task 5: Source-Specific Blog Image Prompts

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/media/BlogImageGenerationService.java`
- Modify: `backend/src/test/java/com/simonrowe/media/BlogImageGenerationServiceTest.java`
- Continue from Task 4 before committing.

- [ ] **Step 1: Add image prompt tests**

Add this test to `BlogImageGenerationServiceTest`:

```java
  @Test
  void buildPrompt_withSourceContextIncludesEditorialContext() {
    String prompt = service.buildPrompt(
        "What caught my eye: Agent frameworks mature",
        "A few practical notes on agent frameworks.",
        "External sources: Agent frameworks mature from AI Native Dev; Spring AI advisors from Spring Blog");

    assertThat(prompt).contains("Agent frameworks mature");
    assertThat(prompt).contains("AI Native Dev");
    assertThat(prompt).contains("Spring Blog");
    assertThat(prompt).contains("editorial");
    assertThat(prompt).contains("No text");
  }
```

Add this test:

```java
  @Test
  void generateAndStore_withSourceContextPassesContextIntoPrompt() {
    ImageResponse response = new ImageResponse(
        List.of(new ImageGeneration(new Image("https://img.example.com/context.png", null))));
    when(imageModel.call(any(ImagePrompt.class))).thenReturn(response);
    when(externalImageDownloader.downloadAndStore(anyString()))
        .thenReturn("/uploads/context/original.png");

    service.generateAndStore(
        "What caught my eye: Agent frameworks mature",
        "A few practical notes.",
        "External sources: Agent frameworks mature from AI Native Dev");

    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(imageModel).call(captor.capture());
    assertThat(promptOf(captor)).contains("AI Native Dev");
  }
```

- [ ] **Step 2: Run image tests and verify failure**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.media.BlogImageGenerationServiceTest
```

Expected: FAIL because the overloads do not exist.

- [ ] **Step 3: Update prompt template and add overloads**

In `BlogImageGenerationService`, replace `PROMPT_TEMPLATE` with:

```java
  private static final String PROMPT_TEMPLATE =
      "A personal editorial hero image for a technical blog. Use a %s visual "
          + "direction with %s. The image should feel specific to these topics: "
          + "%s. Prefer practical engineering cues such as notes, architecture "
          + "sketches, tooling dashboards, event streams, model orchestration, "
          + "search indexes, or delivery pipelines when relevant. "
          + "No text, no words, no letters, no logos. "
          + "Wide cinematic landscape format.";
```

Replace `COMPOSITIONS` with:

```java
  private static final String[] COMPOSITIONS = {
      "desk/workbench scene with annotated system sketches and browser windows",
      "architecture sketchbook with service boundaries and data flow arrows",
      "tooling dashboard with charts, logs, and deployment signals",
      "event-stream visualization with messages flowing between services",
      "model orchestration workspace with prompts, tools, and retrieval layers",
      "search index map with documents, vectors, and ranked result paths",
      "testing and delivery pipeline with checks, traces, and release gates",
      "curated reading board with pinned article cards and technical notes",
  };
```

Add an overload:

```java
  public String generateAndStore(
      final String blogTitle,
      final String blogSummary) {
    return generateAndStore(blogTitle, blogSummary, null);
  }

  public String generateAndStore(
      final String blogTitle,
      final String blogSummary,
      final String sourceContext) {
    try {
      String prompt = buildPrompt(blogTitle, blogSummary, sourceContext);

      OpenAiImageOptions options = OpenAiImageOptions.builder()
          .model(IMAGE_MODEL)
          .width(IMAGE_WIDTH)
          .height(IMAGE_HEIGHT)
          .quality(IMAGE_QUALITY)
          .build();

      ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));
      Image output = response.getResult().getOutput();

      String imageUrl = output.getUrl();
      if (imageUrl != null && !imageUrl.isBlank()) {
        log.info("Generated image for blog '{}', downloading from {}", blogTitle, imageUrl);
        return externalImageDownloader.downloadAndStore(imageUrl);
      }

      String b64 = output.getB64Json();
      if (b64 != null && !b64.isBlank()) {
        log.info("Generated image for blog '{}' (inline base64)", blogTitle);
        byte[] bytes = Base64.getDecoder().decode(b64);
        return externalImageDownloader.storeImageBytes(bytes, "png", blogTitle);
      }

      log.warn("Image model returned neither URL nor base64 for blog: {}", blogTitle);
      return null;

    } catch (Exception e) {
      log.warn("Failed to generate image for blog '{}': {}", blogTitle, e.getMessage());
      return null;
    }
  }
```

- [ ] **Step 4: Add `buildPrompt` overload**

Keep the existing two-argument method and make it delegate:

```java
  String buildPrompt(final String blogTitle, final String blogSummary) {
    return buildPrompt(blogTitle, blogSummary, null);
  }

  String buildPrompt(
      final String blogTitle,
      final String blogSummary,
      final String sourceContext) {
    String title = blogTitle == null ? "" : blogTitle;
    int hash = title.hashCode();
    String colorTheme = COLOR_THEMES[Math.floorMod(hash, COLOR_THEMES.length)];
    String composition = COMPOSITIONS[Math.floorMod(hash * 31 + 17, COMPOSITIONS.length)];
    String visualDescription = buildVisualDescription(title, blogSummary, sourceContext);
    return String.format(PROMPT_TEMPLATE, colorTheme, composition, visualDescription);
  }
```

Replace `buildVisualDescription` with:

```java
  private String buildVisualDescription(
      final String blogTitle,
      final String blogSummary,
      final String sourceContext) {
    StringBuilder sb = new StringBuilder();
    sb.append("editorial technical concepts related to: ").append(blogTitle);
    if (blogSummary != null && !blogSummary.isBlank()) {
      sb.append(". ").append(blogSummary);
    }
    if (sourceContext != null && !sourceContext.isBlank()) {
      sb.append(". Source context: ").append(sourceContext);
    }
    return sb.toString();
  }
```

- [ ] **Step 5: Re-run image and digest tests**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.media.BlogImageGenerationServiceTest --tests com.simonrowe.agents.WeeklyDigestAgentTest
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4 and Task 5 together**

```bash
git add backend/src/main/java/com/simonrowe/agents backend/src/main/java/com/simonrowe/media/BlogImageGenerationService.java backend/src/test/java/com/simonrowe/agents/WeeklyDigestAgentTest.java backend/src/test/java/com/simonrowe/media/BlogImageGenerationServiceTest.java
git commit -m "[FEAT] Personalize digest titles and images"
```

---

### Task 6: Chat Prompt Guidance

**Files:**
- Modify: `backend/src/main/java/com/simonrowe/chat/ChatConfig.java`
- Create: `backend/src/test/java/com/simonrowe/chat/ChatConfigPromptTest.java`

- [ ] **Step 1: Add a prompt-fragment accessor for testability**

In `ChatConfig`, add:

```java
  static String widgetPromptGuidance() {
    return "When you call the skills, jobs, code example, blog, news, or event tools, "
        + "the visitor already sees a visual card with the details. Add a brief "
        + "framing sentence and do not re-list the data the card shows. "
        + "Do not start a new answer unless the visitor has sent a new prompt.";
  }
```

Update `.defaultSystem(...)` to use it:

```java
        .defaultSystem(systemPrompt + "\n\n" + widgetPromptGuidance())
```

- [ ] **Step 2: Add prompt guidance test**

Create `backend/src/test/java/com/simonrowe/chat/ChatConfigPromptTest.java`:

```java
package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatConfigPromptTest {

  @Test
  void widgetPromptGuidanceCoversSourceWidgetsAndUnpromptedAnswers() {
    String guidance = ChatConfig.widgetPromptGuidance();

    assertThat(guidance).contains("blog");
    assertThat(guidance).contains("news");
    assertThat(guidance).contains("event");
    assertThat(guidance).contains("do not re-list");
    assertThat(guidance).contains("unless the visitor has sent a new prompt");
  }
}
```

- [ ] **Step 3: Run prompt test**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.chat.ChatConfigPromptTest
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/simonrowe/chat/ChatConfig.java backend/src/test/java/com/simonrowe/chat/ChatConfigPromptTest.java
git commit -m "[FEAT] Tighten chat widget prompt guidance"
```

---

### Task 7: Full Verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Run focused frontend tests**

Run:

```bash
cd frontend && npm test -- src/components/chat/ChatPanel.test.tsx src/components/chat/widgets/ChatWidgets.test.tsx src/components/chat/widgets/ChatWidgetRegistry.test.tsx
```

Expected: PASS.

- [ ] **Step 2: Run focused backend tests**

Run:

```bash
./gradlew :backend:test --tests com.simonrowe.mcp.ProfileMcpToolsTest --tests com.simonrowe.media.BlogImageGenerationServiceTest --tests com.simonrowe.agents.WeeklyDigestAgentTest --tests com.simonrowe.chat.ChatConfigPromptTest
```

Expected: PASS.

- [ ] **Step 3: Run frontend typecheck/build**

Run:

```bash
cd frontend && npm run build
```

Expected: PASS.

- [ ] **Step 4: Run backend test suite**

Run:

```bash
./gradlew :backend:test
```

Expected: PASS.

- [ ] **Step 5: Inspect final diff**

Run:

```bash
git status --short
git diff origin/main... --stat
```

Expected: only the chat widget, digest/image generation, tests, design, and plan files are changed.

- [ ] **Step 6: Manual smoke test**

Start the app using the repository's normal local workflow. If services are already running, restart only the service that changed.

Use the homepage chat and run:

```text
What has Simon been paying attention to in AI and backend engineering recently?
```

Expected:

- No assistant message appears before submitting the prompt.
- News/event/blog cards appear when tools return source data.
- Cards with image URLs render images.
- Cards without image URLs have no empty image slot.
- The assistant gives a short synthesis and stops after one response.

Then run:

```text
Talk me through Simon's experience with Spring Boot, Kafka, search, and AI.
Show me concrete examples of how Simon builds production-grade services.
What about testing?
```

Expected:

- Skills/jobs/blog/code widgets still render.
- The testing follow-up stays grounded in the prior topic.
- The assistant does not re-list every card field in prose.

- [ ] **Step 7: Commit verification fixes if needed**

If verification required small fixes, commit them:

```bash
git status --short
git add frontend/src/components/chat backend/src/main/java/com/simonrowe backend/src/test/java/com/simonrowe
git commit -m "[FEAT] Polish chat source widget implementation"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review Checklist

- Spec coverage:
  - Technical hiring manager persona and market-awareness prompt are covered by manual smoke tests and widget tests.
  - Unprompted/random chat behavior is covered by `ChatPanel.test.tsx` and prompt guidance.
  - Image-capable widgets are covered by blog/news/event widget tests.
  - News/events as chat sources are covered by `ProfileMcpToolsTest`.
  - Personal editorial digest titles and descriptions are covered by `WeeklyDigestAgentTest`.
  - Source-specific image prompts are covered by `BlogImageGenerationServiceTest`.
- Placeholder scan:
  - The plan contains no deferred implementation markers.
  - Code snippets define the types and methods they reference.
- Type consistency:
  - Frontend uses `news` with `articles`, `events` with `events`, and `blogs` with `posts`.
  - Backend uses `NewsWidgetPayload`, `EventWidgetPayload`, and updated `BlogWidgetPayload.Post`.
  - Widget kind strings match across backend and frontend: `blogs`, `news`, `events`.
