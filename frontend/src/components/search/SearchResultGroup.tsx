import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { SearchResult } from '../../services/searchApi'
import { useDrawer } from '../../hooks/useDrawer'

/**
 * A result's image, or a neutral tile when it has none or it fails to load.
 *
 * The tile is drawn, not fetched. The previous fallback pointed `onError` at
 * `/images/placeholder.png`, a file that does not exist, so each failure set the same
 * missing source again and fired `onError` again: an endless reload loop, seen as thumbnails
 * flickering while the results were open. Failing once and staying failed is the fix.
 */
function ResultThumbnail({ src }: { src: string | null | undefined }) {
  const [failed, setFailed] = useState(false)
  if (!src || failed) {
    return <span aria-hidden="true" className="search-result-group__thumbnail search-result-group__thumbnail--empty" />
  }
  return (
    <img
      alt=""
      className="search-result-group__thumbnail"
      onError={() => setFailed(true)}
      src={src}
    />
  )
}

const JOB_URL_RE = /^\/jobs\/(.+)$/
const SKILL_GROUP_URL_RE = /^\/skills-groups\/(.+)$/

interface SearchResultGroupProps {
  title: string
  results: SearchResult[]
  onResultClick: () => void
}

export function SearchResultGroup({ title, results, onResultClick }: SearchResultGroupProps) {
  const navigate = useNavigate()
  const { openJob, openSkillGroup } = useDrawer()

  function handleClick(url: string) {
    onResultClick()

    if (url.startsWith('http')) {
      window.open(url, '_blank', 'noopener,noreferrer')
      return
    }

    const jobMatch = JOB_URL_RE.exec(url)
    if (jobMatch) {
      openJob(jobMatch[1])
      return
    }

    const skillMatch = SKILL_GROUP_URL_RE.exec(url)
    if (skillMatch) {
      openSkillGroup(skillMatch[1])
      return
    }

    void navigate(url)
  }

  return (
    <div className="search-result-group">
      <h4 className="search-result-group__title">{title}</h4>
      <ul className="search-result-group__list">
        {results.map((result) => (
          <li className="search-result-group__item" key={result.url + result.name}>
            <button
              className="search-result-group__link"
              onClick={() => handleClick(result.url)}
              type="button"
            >
              <ResultThumbnail src={result.image} />
              <span className="search-result-group__name">{result.name}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
