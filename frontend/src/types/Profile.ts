import type { SocialMediaLink } from './SocialMediaLink'

export interface ImageAsset {
  url: string
  name?: string
  width?: number
  height?: number
  mime?: string
  formats?: ImageFormats
}

export interface ImageFormats {
  thumbnail?: ImageAsset
  small?: ImageAsset
  medium?: ImageAsset
  large?: ImageAsset
}

export interface Profile {
  name: string
  firstName: string
  lastName: string
  title: string
  headline: string
  description: string
  profileImage: ImageAsset
  sidebarImage: ImageAsset
  backgroundImage: ImageAsset
  mobileBackgroundImage: ImageAsset
  location: string
  phoneNumber: string
  primaryEmail: string
  secondaryEmail?: string
  cvUrl?: string | null
  socialMediaLinks: SocialMediaLink[]
}
