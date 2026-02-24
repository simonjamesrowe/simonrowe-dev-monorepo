import { useTour } from '../../hooks/useTour'

export function TourButton() {
  const { start } = useTour()

  return (
    <button
      className="tour-button button"
      onClick={() => void start()}
      type="button"
    >
      Take a Tour
    </button>
  )
}
