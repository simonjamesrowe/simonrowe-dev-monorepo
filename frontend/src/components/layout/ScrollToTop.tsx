import { useEffect, useState } from 'react'

interface ScrollToTopProps {
  onScrollToTop?: () => void
}

export function ScrollToTop({ onScrollToTop }: ScrollToTopProps) {
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const onScroll = () => {
      setVisible(window.scrollY > 600)
    }

    onScroll()
    window.addEventListener('scroll', onScroll)

    return () => {
      window.removeEventListener('scroll', onScroll)
    }
  }, [])

  if (!visible) {
    return null
  }

  return (
    <button
      aria-label="Scroll to top"
      className="scroll-to-top"
      onClick={() => {
        window.scrollTo({ top: 0, behavior: 'smooth' })
        onScrollToTop?.()
      }}
      type="button"
    >
      Top
    </button>
  )
}
