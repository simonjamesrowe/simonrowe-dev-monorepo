/* global React, ReactDOM, lucide */
const { useState, useEffect, useRef, useCallback } = React;

const PROMPTS = [
  "What Spring Boot & Kafka patterns does he use?",
  "How does he approach AI-native engineering?",
  "How big are the teams he's led?",
  "What is he blogging about recently?",
];

/* Concise persona so the live model answers in-character on Simon's behalf. */
const PERSONA = `You are the AI assistant on Simon Rowe's personal website, simonrowe.dev.
You answer questions about Simon in a warm, confident, concise way (2-4 sentences, first person about Simon, e.g. "Simon leads...").
Avoid hype. Be specific about technologies and scale. If you don't know, point to his CV or blog.

About Simon Rowe:
- Head of Engineering, Commercial Trading at Global (Aug 2021-present). Leads 30+ engineers across three product pillars, driving AI-native transformation and platform modernization.
- Previously: Senior Developer at Y-Tree (fin-tech, event-driven + RESTful microservices), Software Engineering Lead at Upp Technologies, Senior Platform Architect at Pivotal (cloud-native), Senior Director of Java Development at Universal Music Publishing, plus earlier roles at Macquarie Group, SAS and Civica.
- BSc Computer Science, University of Newcastle (WAM 81.24).
- Stack: Java, Kotlin, Spring Boot 3, Kafka (outbox pattern, schema-registry Avro, idempotent consumers, Kafka Streams), Kubernetes, TypeScript & React.
- Architecture: event-driven microservices, pragmatic event sourcing + CQRS where audit/time-travel matter (most services start CRUD), the outbox pattern.
- AI-native engineering: Claude Code, MCP integration, structured evaluation frameworks / weekly prompt evals. Belief: "AI amplifies good engineering, it doesn't replace it."
- Blogs roughly twice a month on architecture, AI-native workflows and event sourcing.`;

const FALLBACK = {
  spring: "Simon reaches for Spring Boot 3 with Kafka and the outbox pattern for transactional events — schema-registry-backed Avro contracts and idempotent consumers using Kafka Streams. He writes about evolving these under the 'Architecture' tag on his blog.",
  ai: "Simon is a strong advocate for AI-native engineering — Claude Code, MCP integration and structured evaluation frameworks, with his teams running weekly evals on every prompt. His core belief is that AI amplifies good engineering, it doesn't replace it.",
  team: "Currently 30+ engineers at Global across three product pillars. Earlier he led teams of 8–12 at Y-Tree and Upp, and he's a firm believer in sub-team autonomy backed by shared platform standards.",
  blog: "Recent posts cover AI-native development workflows, the event-sourcing patterns he keeps reaching for, and why his team runs weekly evals on every prompt. He posts roughly twice a month.",
  event: "He favours event sourcing where audit and time-travel matter, with a pragmatic CQRS split — aggregates on the write side, projections rebuilt from the log on the read side. He's emphatic that most services should start CRUD.",
  default: "Great question — Simon's CV and blog go deeper on most engineering topics. He leads engineering at Global and specialises in AI-native, cloud-native and event-driven systems.",
};

function pickFallback(q) {
  const t = q.toLowerCase();
  if (/(spring|kafka|stream|outbox)/.test(t)) return FALLBACK.spring;
  if (/(ai|claude|llm|mcp|eval)/.test(t)) return FALLBACK.ai;
  if (/(team|engineer|manage|lead|people|big)/.test(t)) return FALLBACK.team;
  if (/(blog|writ|post|recent|article)/.test(t)) return FALLBACK.blog;
  if (/(event|cqrs|sourc|architect|pattern)/.test(t)) return FALLBACK.event;
  return FALLBACK.default;
}

function Chat({ onClose }) {
  const [messages, setMessages] = useState([
    { role: "assistant", text: "Hi — I'm Simon's AI, trained on his experience, skills and career history. Ask me anything about his engineering leadership, his stack, or how he works." },
  ]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const scrollRef = useRef(null);
  const inputRef = useRef(null);

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages, busy]);

  useEffect(() => { if (window.lucide) window.lucide.createIcons(); });

  const send = useCallback(async (text) => {
    const t = (text != null ? text : draft).trim();
    if (!t || busy) return;
    const next = [...messages, { role: "user", text: t }];
    setMessages(next);
    setDraft("");
    setBusy(true);

    let reply;
    try {
      if (window.claude && window.claude.complete) {
        const convo = next
          .map((m) => `${m.role === "user" ? "Visitor" : "Assistant"}: ${m.text}`)
          .join("\n");
        const prompt = `${PERSONA}\n\nConversation so far:\n${convo}\nAssistant:`;
        const raw = await window.claude.complete(prompt);
        reply = (raw || "").trim();
      }
    } catch (e) {
      reply = null;
    }
    if (!reply) reply = pickFallback(t);

    setBusy(false);
    setMessages((m) => [...m, { role: "assistant", text: reply }]);
    if (inputRef.current) inputRef.current.focus();
  }, [draft, busy, messages]);

  const fresh = messages.length === 1 && !busy;

  return (
    <div className="chat">
      <header className="chat__head">
        <div className="chat__avatar">SR<span className="chat__pulse" /></div>
        <div className="chat__id">
          <div className="chat__name">Simon's AI</div>
          <div className="chat__sub">Trained on his experience &amp; career</div>
        </div>
        {onClose
          ? <button className="icon-btn chat__close" onClick={onClose} aria-label="Close chat"><i data-lucide="x"></i></button>
          : <span className="chat__status"><span className="dot" /> Online</span>}
      </header>

      <div className="chat__body" ref={scrollRef}>
        {messages.map((m, i) => (
          <div key={i} className={`msg msg--${m.role}`}>
            {m.role === "assistant" && <div className="msg__avatar">SR</div>}
            <div className="msg__bubble">{m.text}</div>
          </div>
        ))}
        {busy && (
          <div className="msg msg--assistant">
            <div className="msg__avatar">SR</div>
            <div className="msg__bubble msg__typing"><span /><span /><span /></div>
          </div>
        )}
      </div>

      {fresh && (
        <div className="chat__prompts">
          <div className="chat__prompts-label">Try asking</div>
          {PROMPTS.map((p) => (
            <button key={p} className="chat__prompt" onClick={() => send(p)}>{p}</button>
          ))}
        </div>
      )}

      <form className="chat__composer" onSubmit={(e) => { e.preventDefault(); send(); }}>
        <input
          ref={inputRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="Ask me anything about Simon…"
          aria-label="Ask Simon's AI a question"
        />
        <button type="submit" className="chat__send" disabled={busy || !draft.trim()} aria-label="Send">
          <i data-lucide="arrow-up"></i>
        </button>
      </form>
    </div>
  );
}

/* Floating "Ask AI" button + slide-in drawer for sub-pages */
function ChatDock() {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    document.body.style.overflow = open ? "hidden" : "";
    return () => { document.body.style.overflow = ""; };
  }, [open]);

  useEffect(() => {
    function onKey(e) { if (e.key === "Escape") setOpen(false); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => { if (window.lucide) window.lucide.createIcons(); }, [open]);

  return (
    <>
      <button
        className={`chat-fab ${open ? "is-hidden" : ""}`}
        onClick={() => setOpen(true)}
        aria-label="Ask Simon's AI"
      >
        <i data-lucide="sparkles"></i>
        <span>Ask AI</span>
      </button>

      <div className={`chat-scrim ${open ? "is-open" : ""}`} onClick={() => setOpen(false)} />
      <aside className={`chat-drawer ${open ? "is-open" : ""}`} role="dialog" aria-label="Chat with Simon's AI" aria-hidden={!open}>
        {open && <Chat onClose={() => setOpen(false)} />}
      </aside>
    </>
  );
}

window.SimonChat = Chat;
window.SimonChatDock = ChatDock;

const inlineEl = document.getElementById("chat-root");
if (inlineEl) ReactDOM.createRoot(inlineEl).render(<Chat />);

const dockEl = document.getElementById("chat-dock");
if (dockEl) ReactDOM.createRoot(dockEl).render(<ChatDock />);
