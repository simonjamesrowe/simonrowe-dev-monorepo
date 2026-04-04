import { useProfile } from '../../hooks/useProfile'

export function Footer() {
  const { profile } = useProfile()

  const githubLink = profile?.socialMediaLinks?.find(l => l.type === 'github')
  const linkedinLink = profile?.socialMediaLinks?.find(l => l.type === 'linkedin')

  return (
    <footer className="footer">
      <div className="footer__brand">
        <p className="footer__copyright">© {new Date().getFullYear()} {profile?.name ?? 'Simon Rowe'}. All rights reserved.</p>
      </div>
      <div className="footer__links">
        {githubLink && (
          <a href={githubLink.url} target="_blank" rel="noopener noreferrer" className="footer__link">GitHub</a>
        )}
        {linkedinLink && (
          <a href={linkedinLink.url} target="_blank" rel="noopener noreferrer" className="footer__link">LinkedIn</a>
        )}
      </div>
    </footer>
  )
}
