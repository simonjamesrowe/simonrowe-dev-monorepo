/* global React, Icon, AButton, Status */
const { useState } = React;

const SAMPLE_MD = `# Building AI-native development workflows

How my team uses Claude Code, evals, and structured review to ship faster
without losing quality.

## The shape of the problem

Most teams adopt AI tooling **bottom-up** — individuals try it, like it,
and it leaks into the workflow. That's fine, but it doesn't compound.

> AI amplifies good engineering, it doesn't replace it.

We needed a *team-level* rhythm. Three rituals, weekly:

1. **Prompt review** — every prompt change goes through a PR
2. **Eval pass** — automated checks against a golden set
3. **Pattern share** — 15 min on what worked, what didn't

\`\`\`ts
// the eval skeleton we keep reaching for
runEvals(promptVersion, goldenSet, {
  judge: 'claude-haiku-4-5',
  threshold: 0.85,
});
\`\`\`
`;

function BlogEditor({ post, onClose }) {
  const [title, setTitle] = useState(post?.title || "Building AI-native development workflows");
  const [body, setBody] = useState(SAMPLE_MD);
  const [tab, setTab] = useState("write");

  return (
    <div className="a-editor">
      <div className="a-editor__bar">
        <div className="a-editor__breadcrumb">
          <button className="a-iconbtn" onClick={onClose}><Icon name="arrow-left" size={15} /></button>
          <span>Blog</span><Icon name="chevron-right" size={12} /><span className="muted">{title}</span>
        </div>
        <div className="a-editor__bar-actions">
          <Status kind="draft" />
          <AButton variant="ghost" icon="eye">Preview</AButton>
          <AButton variant="secondary" icon="save">Save draft</AButton>
          <AButton variant="primary" icon="send">Publish</AButton>
        </div>
      </div>

      <div className="a-editor__title-row">
        <input
          className="a-editor__title"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Post title…"
        />
        <div className="a-editor__meta">
          <span className="a-tag">AI</span>
          <span className="a-tag">Engineering</span>
          <button className="a-tag a-tag--add"><Icon name="plus" size={11} /> Tag</button>
        </div>
      </div>

      <div className="a-editor__tabs">
        {["write", "split", "preview"].map((t) => (
          <button key={t} className={`a-editor__tab ${tab === t ? "active" : ""}`} onClick={() => setTab(t)}>
            <Icon name={t === "write" ? "edit-3" : t === "split" ? "columns" : "eye"} size={13} />
            {t[0].toUpperCase() + t.slice(1)}
          </button>
        ))}
        <div className="a-editor__toolbar">
          {["bold", "italic", "list", "link", "code", "image", "quote"].map((t) => (
            <button key={t} className="a-editor__tbtn" aria-label={t}>
              <Icon name={t === "list" ? "list" : t === "link" ? "link" : t === "code" ? "code" : t === "image" ? "image" : t === "quote" ? "quote" : t} size={14} />
            </button>
          ))}
        </div>
      </div>

      <div className={`a-editor__panes a-editor__panes--${tab}`}>
        {(tab === "write" || tab === "split") && (
          <textarea
            className="a-editor__textarea"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            spellCheck="false"
          />
        )}
        {(tab === "preview" || tab === "split") && (
          <div className="a-editor__preview">
            <Rendered md={body} />
          </div>
        )}
      </div>
    </div>
  );
}

/* extremely tiny markdown renderer — just enough for the demo */
function Rendered({ md }) {
  const lines = md.split("\n");
  const out = [];
  let inCode = false; let codeBuf = [];
  let inList = false; let listBuf = [];
  const flushList = () => {
    if (inList) { out.push(<ol key={out.length}>{listBuf.map((l, i) => <li key={i} dangerouslySetInnerHTML={{__html: inlineMd(l)}}/>)}</ol>); listBuf = []; inList = false; }
  };
  for (const line of lines) {
    if (line.startsWith("```")) {
      if (inCode) { out.push(<pre key={out.length}><code>{codeBuf.join("\n")}</code></pre>); codeBuf = []; inCode = false; }
      else { inCode = true; }
      continue;
    }
    if (inCode) { codeBuf.push(line); continue; }
    if (/^\d+\.\s/.test(line)) { inList = true; listBuf.push(line.replace(/^\d+\.\s/, "")); continue; }
    flushList();
    if (line.startsWith("# ")) out.push(<h1 key={out.length}>{line.slice(2)}</h1>);
    else if (line.startsWith("## ")) out.push(<h2 key={out.length}>{line.slice(3)}</h2>);
    else if (line.startsWith("> ")) out.push(<blockquote key={out.length} dangerouslySetInnerHTML={{__html: inlineMd(line.slice(2))}}/>);
    else if (line.trim() === "") out.push(<div key={out.length} style={{height: 4}}/>);
    else out.push(<p key={out.length} dangerouslySetInnerHTML={{__html: inlineMd(line)}}/>);
  }
  flushList();
  return <>{out}</>;
}
function inlineMd(s) {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>');
}

window.BlogEditor = BlogEditor;
