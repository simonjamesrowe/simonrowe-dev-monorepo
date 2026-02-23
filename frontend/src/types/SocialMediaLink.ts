export type SocialMediaPlatform = 'github' | 'linkedin' | 'twitter'

export interface SocialMediaLink {
  type: SocialMediaPlatform
  name: string
  url: string
  includeOnResume?: boolean
}
