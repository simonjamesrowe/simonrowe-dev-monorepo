export interface IImageFormat {
  url: string
  width?: number
  height?: number
}

export interface IImage {
  url: string
  name?: string
  width?: number
  height?: number
  mime?: string
  formats?: {
    thumbnail?: IImageFormat
    small?: IImageFormat
    medium?: IImageFormat
    large?: IImageFormat
  }
}
