export interface TourStep {
  id: string
  order: number
  targetSelector: string
  title: string
  titleImage: string | null
  description: string
  position: 'top' | 'bottom' | 'left' | 'right' | 'center'
}
