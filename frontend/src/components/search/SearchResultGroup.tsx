import { useNavigate } from 'react-router-dom'
import type { SearchResult } from '../../services/searchApi'
import { useDrawer } from '../../hooks/useDrawer'

const PLACEHOLDER_IMAGE = '/images/placeholder.png'

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
              <img
                alt=""
                className="search-result-group__thumbnail"
                onError={(e) => {
                  (e.currentTarget as HTMLImageElement).src = PLACEHOLDER_IMAGE
                }}
                src={result.image ?? PLACEHOLDER_IMAGE}
              />
              <span className="search-result-group__name">{result.name}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
