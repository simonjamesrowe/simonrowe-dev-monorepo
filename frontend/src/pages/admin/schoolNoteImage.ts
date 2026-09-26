const MAX_EDGE = 2000
const JPEG_QUALITY = 0.85

/**
 * Makes a phone photo cheap and predictable to upload while preserving browser-applied EXIF
 * orientation. The selected image URL is shared with the visible preview and owned by the page.
 */
export function prepareSchoolNoteImage(sourceUrl: string): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => {
      const longestEdge = Math.max(image.naturalWidth, image.naturalHeight)
      const scale = longestEdge > MAX_EDGE ? MAX_EDGE / longestEdge : 1
      const width = Math.max(1, Math.round(image.naturalWidth * scale))
      const height = Math.max(1, Math.round(image.naturalHeight * scale))
      const canvas = document.createElement('canvas')
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d')
      if (!context) {
        reject(new Error('This browser could not prepare that photo.'))
        return
      }
      context.drawImage(image, 0, 0, width, height)
      canvas.toBlob(
        (blob) => {
          if (blob) {
            resolve(blob)
          } else {
            reject(new Error('This browser could not prepare that photo.'))
          }
        },
        'image/jpeg',
        JPEG_QUALITY,
      )
    }
    image.onerror = () => reject(new Error('That photo could not be opened.'))
    image.src = sourceUrl
  })
}
