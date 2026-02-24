import { useNavigate } from 'react-router-dom'
import type { SearchResult } from '../../services/searchApi'

const PLACEHOLDER_IMAGE = '/images/placeholder.png'

interface SearchResultGroupProps {
  title: string
  results: SearchResult[]
  onResultClick: () => void
}

export function SearchResultGroup({ title, results, onResultClick }: SearchResultGroupProps) {
  const navigate = useNavigate()

  function handleClick(url: string) {
    onResultClick()
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
