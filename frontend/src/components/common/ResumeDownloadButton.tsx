interface ResumeDownloadButtonProps {
  onDownload?: () => void
}

export function ResumeDownloadButton({ onDownload }: ResumeDownloadButtonProps) {
  const handleClick = () => {
    onDownload?.()
    window.open('/api/resume', '_blank')
  }

  return (
    <button className="button resume-download-button" onClick={handleClick} type="button">
      Download Resume
    </button>
  )
}
