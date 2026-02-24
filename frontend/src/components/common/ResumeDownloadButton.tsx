import { API_BASE_URL } from '../../config/api'

interface ResumeDownloadButtonProps {
  onDownload?: () => void
}

export function ResumeDownloadButton({ onDownload }: ResumeDownloadButtonProps) {
  const handleClick = () => {
    onDownload?.()
    window.open(`${API_BASE_URL}/api/resume`, '_blank')
  }

  return (
    <button className="button resume-download-button" onClick={handleClick} type="button">
      Download Resume
    </button>
  )
}
