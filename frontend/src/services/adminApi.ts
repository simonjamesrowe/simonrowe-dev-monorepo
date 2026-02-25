import { API_BASE_URL } from '../config/api'

const ADMIN_URL = `${API_BASE_URL}/api/admin`

// ---------------------------------------------------------------------------
// Entity types
// ---------------------------------------------------------------------------

export interface AdminBlog {
  id: string
  title: string
  shortDescription: string
  content: string
  published: boolean
  featuredImage: string | null
  tags: string[]
  skills: string[]
  createdAt: string
  updatedAt: string
}

export interface AdminJob {
  id: string
  title: string
  company: string
  companyUrl: string | null
  companyImage: string | null
  startDate: string
  endDate: string | null
  location: string | null
  shortDescription: string
  longDescription: string | null
  education: boolean
  includeOnResume: boolean
  skills: string[]
  createdAt: string
  updatedAt: string
}

export interface AdminSkill {
  id: string
  name: string
  rating: number | null
  description: string | null
  image: string | null
  order: number
  createdAt: string
  updatedAt: string
}

export interface AdminSkillGroup {
  id: string
  name: string
  rating: number | null
  description: string | null
  image: string | null
  order: number
  skills: string[]
  createdAt: string
  updatedAt: string
}

export interface AdminProfile {
  id: string
  name: string
  title: string
  headline: string | null
  description: string | null
  location: string | null
  phoneNumber: string | null
  primaryEmail: string | null
  secondaryEmail: string | null
  profileImage: string | null
  sidebarImage: string | null
  backgroundImage: string | null
  mobileBackgroundImage: string | null
  createdAt: string
  updatedAt: string
}

export interface AdminSocialMedia {
  id: string
  type: string
  link: string
  name: string | null
  includeOnResume: boolean
  createdAt: string
  updatedAt: string
}

export interface AdminTag {
  id: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface AdminTourStep {
  id: string
  title: string
  selector: string
  description: string | null
  titleImage: string | null
  position: string | null
  order: number
  createdAt: string
  updatedAt: string
}

export interface MediaAsset {
  id: string
  fileName: string
  mimeType: string
  fileSize: number
  originalPath: string
  variants: Record<string, { path: string; width: number; height: number; fileSize: number }>
  createdAt: string
  updatedAt: string
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

type GetAccessToken = () => Promise<string>

async function authFetch(url: string, token: string, options?: RequestInit): Promise<Response> {
  return fetch(url, {
    ...options,
    headers: {
      ...options?.headers,
      Authorization: `Bearer ${token}`,
    },
  })
}

async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = 'Request failed.'
    try {
      const errorPayload = await response.json()
      if (typeof errorPayload.message === 'string' && errorPayload.message.trim() !== '') {
        message = errorPayload.message
      }
    } catch {
      // Keep default fallback message when the response has no JSON payload.
    }
    throw new Error(message)
  }
  return (await response.json()) as T
}

function jsonOptions(data: Record<string, unknown>, method: string): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  }
}

// ---------------------------------------------------------------------------
// Blogs
// ---------------------------------------------------------------------------

export async function fetchAdminBlogs(
  getAccessToken: GetAccessToken,
  page = 0,
  size = 20,
): Promise<PageResponse<AdminBlog>> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/blogs?page=${page}&size=${size}`, token)
  return handleResponse<PageResponse<AdminBlog>>(response)
}

export async function fetchAdminBlogById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminBlog> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/blogs/${id}`, token)
  return handleResponse<AdminBlog>(response)
}

export async function createAdminBlog(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminBlog> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/blogs`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminBlog>(response)
}

export async function updateAdminBlog(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminBlog> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/blogs/${id}`, token, jsonOptions(data, 'PUT'))
  return handleResponse<AdminBlog>(response)
}

export async function deleteAdminBlog(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/blogs/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Jobs
// ---------------------------------------------------------------------------

export async function fetchAdminJobs(
  getAccessToken: GetAccessToken,
  page = 0,
  size = 20,
): Promise<PageResponse<AdminJob>> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/jobs?page=${page}&size=${size}`, token)
  return handleResponse<PageResponse<AdminJob>>(response)
}

export async function fetchAdminJobsByEducation(
  getAccessToken: GetAccessToken,
  page = 0,
  size = 20,
): Promise<PageResponse<AdminJob>> {
  const token = await getAccessToken()
  const response = await authFetch(
    `${ADMIN_URL}/jobs?education=true&page=${page}&size=${size}`,
    token,
  )
  return handleResponse<PageResponse<AdminJob>>(response)
}

export async function fetchAdminJobById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminJob> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/jobs/${id}`, token)
  return handleResponse<AdminJob>(response)
}

export async function createAdminJob(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminJob> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/jobs`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminJob>(response)
}

export async function updateAdminJob(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminJob> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/jobs/${id}`, token, jsonOptions(data, 'PUT'))
  return handleResponse<AdminJob>(response)
}

export async function deleteAdminJob(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/jobs/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Skills
// ---------------------------------------------------------------------------

export async function fetchAdminSkills(
  getAccessToken: GetAccessToken,
  page = 0,
  size = 20,
): Promise<PageResponse<AdminSkill>> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skills?page=${page}&size=${size}`, token)
  return handleResponse<PageResponse<AdminSkill>>(response)
}

export async function fetchAdminSkillById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminSkill> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skills/${id}`, token)
  return handleResponse<AdminSkill>(response)
}

export async function createAdminSkill(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminSkill> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skills`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminSkill>(response)
}

export async function updateAdminSkill(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminSkill> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skills/${id}`, token, jsonOptions(data, 'PUT'))
  return handleResponse<AdminSkill>(response)
}

export async function deleteAdminSkill(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skills/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

export async function reorderAdminSkills(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skills/reorder`, token, jsonOptions(data, 'PATCH'))
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Skill groups
// ---------------------------------------------------------------------------

export async function fetchAdminSkillGroups(
  getAccessToken: GetAccessToken,
  page = 0,
  size = 20,
): Promise<PageResponse<AdminSkillGroup>> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skill-groups?page=${page}&size=${size}`, token)
  return handleResponse<PageResponse<AdminSkillGroup>>(response)
}

export async function fetchAdminSkillGroupById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminSkillGroup> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skill-groups/${id}`, token)
  return handleResponse<AdminSkillGroup>(response)
}

export async function createAdminSkillGroup(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminSkillGroup> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skill-groups`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminSkillGroup>(response)
}

export async function updateAdminSkillGroup(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminSkillGroup> {
  const token = await getAccessToken()
  const response = await authFetch(
    `${ADMIN_URL}/skill-groups/${id}`,
    token,
    jsonOptions(data, 'PUT'),
  )
  return handleResponse<AdminSkillGroup>(response)
}

export async function deleteAdminSkillGroup(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/skill-groups/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

export async function reorderAdminSkillGroups(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(
    `${ADMIN_URL}/skill-groups/reorder`,
    token,
    jsonOptions(data, 'PATCH'),
  )
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Profile (singleton)
// ---------------------------------------------------------------------------

export async function fetchAdminProfile(getAccessToken: GetAccessToken): Promise<AdminProfile> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/profile`, token)
  return handleResponse<AdminProfile>(response)
}

export async function updateAdminProfile(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminProfile> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/profile`, token, jsonOptions(data, 'PUT'))
  return handleResponse<AdminProfile>(response)
}

// ---------------------------------------------------------------------------
// Social media
// ---------------------------------------------------------------------------

export async function fetchAdminSocialMedia(
  getAccessToken: GetAccessToken,
): Promise<AdminSocialMedia[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/social-media`, token)
  return handleResponse<AdminSocialMedia[]>(response)
}

export async function fetchAdminSocialMediaById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminSocialMedia> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/social-media/${id}`, token)
  return handleResponse<AdminSocialMedia>(response)
}

export async function createAdminSocialMedia(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminSocialMedia> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/social-media`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminSocialMedia>(response)
}

export async function updateAdminSocialMedia(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminSocialMedia> {
  const token = await getAccessToken()
  const response = await authFetch(
    `${ADMIN_URL}/social-media/${id}`,
    token,
    jsonOptions(data, 'PUT'),
  )
  return handleResponse<AdminSocialMedia>(response)
}

export async function deleteAdminSocialMedia(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/social-media/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Tags
// ---------------------------------------------------------------------------

export async function fetchAdminTags(getAccessToken: GetAccessToken): Promise<AdminTag[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tags`, token)
  return handleResponse<AdminTag[]>(response)
}

export async function fetchAdminTagById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminTag> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tags/${id}`, token)
  return handleResponse<AdminTag>(response)
}

export async function createAdminTag(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminTag> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tags`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminTag>(response)
}

export async function bulkCreateAdminTags(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminTag[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tags/bulk`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminTag[]>(response)
}

export async function updateAdminTag(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminTag> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tags/${id}`, token, jsonOptions(data, 'PUT'))
  return handleResponse<AdminTag>(response)
}

export async function deleteAdminTag(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tags/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Tour steps
// ---------------------------------------------------------------------------

export async function fetchAdminTourSteps(
  getAccessToken: GetAccessToken,
): Promise<AdminTourStep[]> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tour-steps`, token)
  return handleResponse<AdminTourStep[]>(response)
}

export async function fetchAdminTourStepById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<AdminTourStep> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tour-steps/${id}`, token)
  return handleResponse<AdminTourStep>(response)
}

export async function createAdminTourStep(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<AdminTourStep> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tour-steps`, token, jsonOptions(data, 'POST'))
  return handleResponse<AdminTourStep>(response)
}

export async function updateAdminTourStep(
  getAccessToken: GetAccessToken,
  id: string,
  data: Record<string, unknown>,
): Promise<AdminTourStep> {
  const token = await getAccessToken()
  const response = await authFetch(
    `${ADMIN_URL}/tour-steps/${id}`,
    token,
    jsonOptions(data, 'PUT'),
  )
  return handleResponse<AdminTourStep>(response)
}

export async function deleteAdminTourStep(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/tour-steps/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}

export async function reorderAdminTourSteps(
  getAccessToken: GetAccessToken,
  data: Record<string, unknown>,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(
    `${ADMIN_URL}/tour-steps/reorder`,
    token,
    jsonOptions(data, 'PATCH'),
  )
  return handleResponse<void>(response)
}

// ---------------------------------------------------------------------------
// Media
// ---------------------------------------------------------------------------

export async function fetchAdminMedia(
  getAccessToken: GetAccessToken,
  page = 0,
  size = 20,
): Promise<PageResponse<MediaAsset>> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/media?page=${page}&size=${size}`, token)
  return handleResponse<PageResponse<MediaAsset>>(response)
}

export async function fetchAdminMediaById(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<MediaAsset> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/media/${id}`, token)
  return handleResponse<MediaAsset>(response)
}

export async function uploadAdminMedia(
  getAccessToken: GetAccessToken,
  file: File,
): Promise<MediaAsset> {
  const token = await getAccessToken()
  const formData = new FormData()
  formData.append('file', file)
  const response = await authFetch(`${ADMIN_URL}/media`, token, {
    method: 'POST',
    body: formData,
  })
  return handleResponse<MediaAsset>(response)
}

export async function deleteAdminMedia(
  getAccessToken: GetAccessToken,
  id: string,
): Promise<void> {
  const token = await getAccessToken()
  const response = await authFetch(`${ADMIN_URL}/media/${id}`, token, { method: 'DELETE' })
  return handleResponse<void>(response)
}
