import type { IImage } from './image'

export interface ISkill {
  id: string
  name: string
  rating: number
  displayOrder: number
  description?: string | null
  image?: IImage | null
}

export interface ISkillGroup {
  id: string
  name: string
  rating: number
  displayOrder: number
  description?: string | null
  image?: IImage | null
  skills: ISkill[]
}

export interface IJobReference {
  id: string
  title: string
  company: string
  startDate: string
  endDate?: string | null
  companyImage?: IImage | null
}

export interface ISkillDetail extends ISkill {
  jobs: IJobReference[]
}

export interface ISkillGroupDetail {
  id: string
  name: string
  rating: number
  displayOrder: number
  description?: string | null
  image?: IImage | null
  skills: ISkillDetail[]
}
