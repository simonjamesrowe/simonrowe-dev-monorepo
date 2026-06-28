/* global React, Icon */
const { useState, useEffect, useRef } = React;

const PROMPTS = [
  "What Spring Boot and Kafka patterns does he use?",
  "What is he blogging about recently?",
  "How does he handle event sourcing and CQRS?",
  "How big are the teams he's led?",
];

const FAKE_REPLIES = {
  "What Spring Boot and Kafka patterns does he use?":
    "Simon's reach-for stack is Spring Boot 3 + Kafka with the outbox pattern for transactional events, schema-registry-backed Avro contracts, and idempotent consumers using Kafka Streams. He's written about evolving these on the blog under the 'Architecture' tag.",
  "What is he blogging about recently?":
    "Recent posts cover AI-native development workflows, event sourcing patterns he keeps reaching for, and a piece on why his team runs weekly evals on every prompt. He posts roughly twice a month.",
  "How does he handle event sourcing and CQRS?":
    "He prefers event sourcing where audit and time-travel matter, with a pragmatic CQRS split — write side as aggregates, read side as projections rebuilt from the log. He's emphatic that not every service needs ES; most should start CRUD.",
  "How big are the teams he's led?":
    "Currently 30+ engineers at Global across three product pillars. Previously led teams of 8–12 at Y-Tree and Upp. He's a strong believer in 'sub-team autonomy with shared platform standards'.",
};

function ChatDrawer({ open, onClose }) {
  const [messages, setMessages] = useState([
    { role: "assistant", text: "Hi! I'm an AI trained on Simon's experience, skills, and career history. Ask me anything." },
  ]);
  const [draft, setDraft] = useState("");
  const [typing, setTyping] = useState(false);
  const scrollRef = useRef(null);

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages, typing]);

  function send(text) {
    const t = (text || draft).trim();
    if (!t) return;
    setMessages((m) => [...m, { role: "user", text: t }]);
    setDraft("");
    setTyping(true);
    setTimeout(() => {
      setTyping(false);
      const reply = FAKE_REPLIES[t] || "Great question — I don't have a canned answer for that here, but Simon's blog and CV go deeper on most engineering topics.";
      setMessages((m) => [...m, { role: "assistant", text: reply }]);
    }, 900);
  }

  if (!open) return null;
  return (
    <>
      <div className="sr-chat__scrim" onClick={onClose} />
      <aside className="sr-chat" role="dialog" aria-label="Chat with Simon's AI">
        <header className="sr-chat__head">
          <div className="sr-chat__title">
            <div className="sr-chat__avatar">
              <span>SR</span>
              <span className="sr-chat__pulse" />
            </div>
            <div>
              <div className="sr-chat__name">Simon's AI</div>
              <div className="sr-chat__sub">Trained on his experience</div>
            </div>
          </div>
          <button className="sr-iconbtn" onClick={onClose} aria-label="Close chat">
            <Icon name="x" size={16} />
          </button>
        </header>

        <div className="sr-chat__body" ref={scrollRef}>
          {messages.map((m, i) => (
            <div key={i} className={`sr-msg sr-msg--${m.role}`}>
              {m.role === "assistant" && <div className="sr-msg__avatar">S</div>}
              <div className="sr-msg__bubble">{m.text}</div>
            </div>
          ))}
          {typing && (
            <div className="sr-msg sr-msg--assistant">
              <div className="sr-msg__avatar">S</div>
              <div className="sr-msg__bubble sr-msg__typing">
                <span /><span /><span />
              </div>
            </div>
          )}
          {messages.length === 1 && !typing && (
            <div className="sr-chat__prompts">
              <div className="sr-chat__prompts-label">Try asking</div>
              {PROMPTS.map((p) => (
                <button key={p} className="sr-chat__prompt" onClick={() => send(p)}>
                  {p}
                </button>
              ))}
            </div>
          )}
        </div>

        <form className="sr-chat__composer" onSubmit={(e) => { e.preventDefault(); send(); }}>
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Ask me anything…"
          />
          <button type="submit" className="sr-chat__send" aria-label="Send">
            <Icon name="arrow-up" size={16} />
          </button>
        </form>
      </aside>
    </>
  );
}

window.ChatDrawer = ChatDrawer;
