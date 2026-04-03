import { Eye, Heart, Plus } from 'lucide-react'
import { Link } from 'react-router-dom'

interface ContentItem {
  id: string
  title: string
  category: string
  imageUrl: string
  views: string
  likes: string
  trending: boolean
}

const placeholderContent: ContentItem[] = [
  {
    id: '1',
    title: 'Architecting Scalable LLM Infrastructure',
    category: 'ENGINEERING',
    imageUrl: '',
    views: '12.4k',
    likes: '892',
    trending: true,
  },
  {
    id: '2',
    title: 'Zero-Trust Models in Modern Portfolios',
    category: 'SECURITY',
    imageUrl: '',
    views: '8.1k',
    likes: '415',
    trending: false,
  },
]

export function TopContent() {
  return (
    <div className="top-content">
      <h3 className="top-content__title">Top Performing Content</h3>
      <div className="top-content__grid">
        {placeholderContent.map((item) => (
          <div key={item.id} className="top-content__card card">
            <div className="top-content__image">
              <span className="top-content__category">{item.category}</span>
              {item.imageUrl ? (
                <img src={item.imageUrl} alt={item.title} />
              ) : (
                <div className="top-content__image-placeholder" />
              )}
            </div>
            <div className="top-content__info">
              <h4 className="top-content__card-title">{item.title}</h4>
              <div className="top-content__stats">
                <span className="top-content__stat"><Eye size={14} /> {item.views}</span>
                <span className="top-content__stat"><Heart size={14} /> {item.likes}</span>
                {item.trending && <span className="top-content__trending">Trending</span>}
              </div>
            </div>
          </div>
        ))}
        <Link to="/admin/blogs/new" className="top-content__draft card">
          <Plus size={32} />
          <span className="top-content__draft-title">Draft New Post</span>
          <span className="top-content__draft-subtitle">Start writing with AI-assist</span>
        </Link>
      </div>
    </div>
  )
}
