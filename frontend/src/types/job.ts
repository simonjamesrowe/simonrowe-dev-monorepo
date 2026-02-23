import type { IImage } from './image'

export interface ISkillReference {
  id: string
  name: string
  rating: number
  image?: IImage | null
  skillGroupId: string
}

export interface IJob {
  id: string
  title: string
  company: string
  companyUrl?: string
  companyImage?: IImage | null
  startDate: string
  endDate?: string | null
  location: string
  shortDescription: string
  isEducation: boolean
  includeOnResume: boolean
}

export interface IJobDetail extends IJob {
  longDescription: string
  skills: ISkillReference[]
}
