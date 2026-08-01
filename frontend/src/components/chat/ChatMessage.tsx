import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { User } from 'lucide-react'
import { ToolActivityBlock } from './ToolActivityBlock'
import { ChatWidget } from './widgets/ChatWidgetRegistry'
import type { ChatBlock } from './chatTypes'
import { buildAllowlist, classifyLink, isAllowedImage } from './linkPolicy'
import { remarkLinkify } from './remarkLinkify'

const REMARK_PLUGINS = [remarkGfm, remarkLinkify]

// No widget detail cards are rendered in chat. Skills, employment, blogs, news and events
// were already hidden because the answer prose carries the same links and inline images,
// making the cards redundant "tool detail". The code-example card was the last one left,
// and it went the same way: a title, a description and a full code block pushed the
// assistant's actual answer far down the transcript, so the tool label ("Fetching code
// examples") is now the whole signal.
//
// Widget blocks are still kept in the message model — the per-message link/image allowlist
// is derived from them — and this set is the one place that decides what renders, so a
// future widget only needs adding here.
const VISIBLE_WIDGET_KINDS = new Set<string>()

interface ChatMessageProps {
  role: 'user' | 'assistant'
  content?: string
  blocks?: ChatBlock[]
  timestamp?: string
  profileImageUrl?: string
}

// Build react-markdown renderers that enforce the safe link/image policy for this
// message. The model can never produce a fabricated or unsafe live link/image: internal
// routes navigate in-site, allowlisted https links open in a new tab, and everything
// else degrades to plain text (links) or is dropped (images).
function createMarkdownComponents(allowlist: ReadonlySet<string>): Components {
  return {
    a({ href, children }) {
      const classification = classifyLink(href, allowlist)
      // Both internal (in-site route) and allowlisted external links open in a NEW TAB
      // so the visitor never loses their chat conversation. Internal hrefs are relative
      // (e.g. /blogs/:id, /experience?job=:id) and resolve against our own origin, so the
      // SPA loads fresh at that route (drawer deep links included). Fabricated/unsafe
      // links fall through to plain text.
      if ((classification === 'internal' || classification === 'external-allowed') && href) {
        return (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            className="chat-message__link"
            data-testid="answer-link"
          >
            {children}
          </a>
        )
      }
      return <>{children}</>
    },
    img({ src, alt }) {
      const source = typeof src === 'string' ? src : undefined
      if (!isAllowedImage(source, allowlist)) {
        return null
      }
      return (
        <img
          src={source}
          alt={alt ?? ''}
          loading="lazy"
          className="chat-message__image"
          data-testid="answer-image"
        />
      )
    },
  }
}

export function ChatMessage({ role, content = '', blocks, timestamp, profileImageUrl }: ChatMessageProps) {
  const isUser = role === 'user'
  const assistantBlocks = !isUser && blocks?.length ? blocks : undefined
  const markdownComponents = createMarkdownComponents(buildAllowlist(blocks, [profileImageUrl]))

  return (
    <div
      className={`chat-message ${isUser ? 'chat-message--user' : 'chat-message--assistant'}`}
      data-testid={`chat-message-${role}`}
    >
      <div className="chat-message__avatar">
        {isUser ? (
          <User size={14} />
        ) : profileImageUrl ? (
          <img src={profileImageUrl} alt="Assistant" className="chat-message__avatar-img" />
        ) : (
          <User size={14} />
        )}
      </div>
      <div>
        <div className="chat-message__bubble">
          {isUser ? (
            content
          ) : assistantBlocks ? (
            <div className="chat-message__blocks">
              {assistantBlocks.map((block, index) => {
                if (block.kind === 'text') {
                  return (
                    <ReactMarkdown
                      remarkPlugins={REMARK_PLUGINS}
                      components={markdownComponents}
                      key={`text-${index}`}
                    >
                      {block.content}
                    </ReactMarkdown>
                  )
                }
                if (block.kind === 'tool') {
                  return <ToolActivityBlock block={block} key={`tool-${index}-${block.label}`} />
                }
                if (!VISIBLE_WIDGET_KINDS.has(block.widgetKind)) {
                  return null
                }
                return (
                  <ChatWidget
                    widgetKind={block.widgetKind}
                    payload={block.payload}
                    key={`widget-${index}-${block.widgetKind}`}
                  />
                )
              })}
            </div>
          ) : (
            <ReactMarkdown
              remarkPlugins={REMARK_PLUGINS}
              components={markdownComponents}
            >
              {content}
            </ReactMarkdown>
          )}
        </div>
        {timestamp && <div className="chat-message__time">{timestamp}</div>}
      </div>
    </div>
  )
}
