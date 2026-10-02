import { MediaPicker } from './MediaPicker'

interface ImagePickerProps {
  value: string | null
  onChange: (url: string) => void
  label?: string
}

/** Chooses an image from the media library. See {@link MediaPicker} for video and captions. */
export function ImagePicker({ value, onChange, label }: ImagePickerProps) {
  return <MediaPicker kind="image" label={label} onChange={onChange} value={value} />
}
