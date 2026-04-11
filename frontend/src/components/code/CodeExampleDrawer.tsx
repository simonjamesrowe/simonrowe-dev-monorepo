import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import ReactMarkdown from 'react-markdown'

import { fetchCodeExample } from '../../services/codeExampleApi'
import type { CodeExample } from '../../services/codeExampleApi'

interface CodeExampleDrawerProps {
  codeExampleId: string
  onClose: () => void
}

export function CodeExampleDrawer({ codeExampleId, onClose }: CodeExampleDrawerProps) {
  const [example, setExample] = useState<CodeExample | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)

    fetchCodeExample(codeExampleId)
      .then(data => {
        if (!cancelled) setExample(data)
      })
      .catch(() => {
        // silently fail, drawer shows loading
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => { cancelled = true }
  }, [codeExampleId])

  useEffect(() => {
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = '' }
  }, [])

  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleEsc)
    return () => document.removeEventListener('keydown', handleEsc)
  }, [onClose])

  return (
    <div className="drawer-overlay" onClick={onClose}>
      <div className="drawer" onClick={e => e.stopPropagation()}>
        <div className="drawer__header">
          <span className="drawer__title">
            {loading ? 'Loading...' : example?.title ?? 'Code Example'}
          </span>
          <button className="drawer__close" onClick={onClose} aria-label="Close" type="button">
            <X size={18} />
          </button>
        </div>
        <div className="drawer__body">
          {loading && (
            <div className="code-drawer__loading">
              <div className="skeleton-text skeleton-pulse" style={{ width: '60%', height: '1.5rem', marginBottom: '1rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '30%', height: '1.25rem', marginBottom: '1.5rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '100%', height: '0.875rem', marginBottom: '0.5rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '100%', height: '0.875rem', marginBottom: '0.5rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '80%', height: '0.875rem', marginBottom: '2rem' }} />
              <div className="skeleton-text skeleton-pulse" style={{ width: '100%', height: '8rem' }} />
            </div>
          )}
          {!loading && example && (
            <>
              <div className="code-drawer__meta">
                <span className="code-drawer__language-badge">{example.language}</span>
              </div>

              <div className="code-drawer__description">
                <ReactMarkdown>{example.description}</ReactMarkdown>
              </div>

              <div className="code-drawer__code-block">
                <pre><code className={`language-${example.language}`}>{example.code}</code></pre>
              </div>

              {example.skills.length > 0 && (
                <div className="code-drawer__skills">
                  <h4 className="code-drawer__skills-title">Skills</h4>
                  <div className="code-drawer__skills-list">
                    {example.skills.map(skill => (
                      <span key={skill} className="code-drawer__skill-tag">{skill}</span>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
