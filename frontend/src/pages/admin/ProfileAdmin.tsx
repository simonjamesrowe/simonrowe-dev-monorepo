import { useCallback, useEffect, useState } from 'react'

import { useAuth } from '../../auth/useAuth'
import {
  fetchAdminProfile,
  updateAdminProfile,
  fetchAdminSocialMedia,
  createAdminSocialMedia,
  updateAdminSocialMedia,
  deleteAdminSocialMedia,
  type AdminProfile,
  type AdminSocialMedia,
} from '../../services/adminApi'
import { useUnsavedChanges } from '../../hooks/useUnsavedChanges'
import { ConfirmDialog } from '../../components/admin/ConfirmDialog'

interface SocialMediaFormState {
  type: string
  link: string
  name: string
  includeOnResume: boolean
}

const emptySocialMediaForm = (): SocialMediaFormState => ({
  type: '',
  link: '',
  name: '',
  includeOnResume: false,
})

export function ProfileAdmin() {
  const { getAccessToken } = useAuth()

  const [profile, setProfile] = useState<AdminProfile | null>(null)
  const [profileLoading, setProfileLoading] = useState(true)
  const [profileSaving, setProfileSaving] = useState(false)
  const [profileError, setProfileError] = useState<string | null>(null)
  const [profileSuccess, setProfileSuccess] = useState(false)
  const [dirty, setDirty] = useState(false)

  useUnsavedChanges(dirty)

  const [form, setForm] = useState<Omit<AdminProfile, 'id' | 'createdAt' | 'updatedAt'>>({
    name: '',
    title: '',
    headline: '',
    description: '',
    location: '',
    phoneNumber: '',
    primaryEmail: '',
    secondaryEmail: '',
    profileImage: '',
    sidebarImage: '',
    backgroundImage: '',
    mobileBackgroundImage: '',
  })

  const [socialMedia, setSocialMedia] = useState<AdminSocialMedia[]>([])
  const [socialMediaLoading, setSocialMediaLoading] = useState(true)
  const [socialMediaError, setSocialMediaError] = useState<string | null>(null)

  const [editingId, setEditingId] = useState<string | null>(null)
  const [editForm, setEditForm] = useState<SocialMediaFormState>(emptySocialMediaForm())
  const [editSaving, setEditSaving] = useState(false)

  const [addForm, setAddForm] = useState<SocialMediaFormState>(emptySocialMediaForm())
  const [addSaving, setAddSaving] = useState(false)
  const [showAddForm, setShowAddForm] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null)

  const loadProfile = useCallback(async () => {
    try {
      setProfileLoading(true)
      setProfileError(null)
      const data = await fetchAdminProfile(getAccessToken)
      setProfile(data)
      setForm({
        name: data.name ?? '',
        title: data.title ?? '',
        headline: data.headline ?? '',
        description: data.description ?? '',
        location: data.location ?? '',
        phoneNumber: data.phoneNumber ?? '',
        primaryEmail: data.primaryEmail ?? '',
        secondaryEmail: data.secondaryEmail ?? '',
        profileImage: data.profileImage ?? '',
        sidebarImage: data.sidebarImage ?? '',
        backgroundImage: data.backgroundImage ?? '',
        mobileBackgroundImage: data.mobileBackgroundImage ?? '',
      })
    } catch (err) {
      setProfileError(err instanceof Error ? err.message : 'Failed to load profile')
    } finally {
      setProfileLoading(false)
    }
  }, [getAccessToken])

  const loadSocialMedia = useCallback(async () => {
    try {
      setSocialMediaLoading(true)
      setSocialMediaError(null)
      const data = await fetchAdminSocialMedia(getAccessToken)
      setSocialMedia(data)
    } catch (err) {
      setSocialMediaError(err instanceof Error ? err.message : 'Failed to load social media')
    } finally {
      setSocialMediaLoading(false)
    }
  }, [getAccessToken])

  useEffect(() => {
    loadProfile()
    loadSocialMedia()
  }, [loadProfile, loadSocialMedia])

  const handleProfileChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>,
  ) => {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
    setDirty(true)
  }

  const handleProfileSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setProfileSaving(true)
      setProfileError(null)
      setProfileSuccess(false)
      const updated = await updateAdminProfile(getAccessToken, form as Record<string, unknown>)
      setProfile(updated)
      setProfileSuccess(true)
      setDirty(false)
    } catch (err) {
      setProfileError(err instanceof Error ? err.message : 'Failed to save profile')
    } finally {
      setProfileSaving(false)
    }
  }

  const handleEditStart = (item: AdminSocialMedia) => {
    setEditingId(item.id)
    setEditForm({
      type: item.type,
      link: item.link,
      name: item.name ?? '',
      includeOnResume: item.includeOnResume,
    })
  }

  const handleEditChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value, type, checked } = e.target
    setEditForm((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
    }))
  }

  const handleEditSave = async (id: string) => {
    try {
      setEditSaving(true)
      await updateAdminSocialMedia(getAccessToken, id, editForm as Record<string, unknown>)
      setEditingId(null)
      await loadSocialMedia()
    } catch (err) {
      setSocialMediaError(err instanceof Error ? err.message : 'Failed to update social media link')
    } finally {
      setEditSaving(false)
    }
  }

  const handleEditCancel = () => {
    setEditingId(null)
    setEditForm(emptySocialMediaForm())
  }

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return
    try {
      await deleteAdminSocialMedia(getAccessToken, deleteTarget.id)
      setDeleteTarget(null)
      await loadSocialMedia()
    } catch (err) {
      setSocialMediaError(
        err instanceof Error ? err.message : 'Failed to delete social media link',
      )
      setDeleteTarget(null)
    }
  }

  const handleAddChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const { name, value, type, checked } = e.target
    setAddForm((prev) => ({
      ...prev,
      [name]: type === 'checkbox' ? checked : value,
    }))
  }

  const handleAddSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      setAddSaving(true)
      await createAdminSocialMedia(getAccessToken, addForm as Record<string, unknown>)
      setAddForm(emptySocialMediaForm())
      setShowAddForm(false)
      await loadSocialMedia()
    } catch (err) {
      setSocialMediaError(err instanceof Error ? err.message : 'Failed to create social media link')
    } finally {
      setAddSaving(false)
    }
  }

  if (profileLoading) {
    return <div className="admin-loading">Loading profile...</div>
  }

  if (!profile && profileError) {
    return (
      <div className="admin-error">
        <p>{profileError}</p>
        <button className="admin-btn" onClick={loadProfile} type="button">
          Retry
        </button>
      </div>
    )
  }

  return (
    <div className="admin-page">
      <h1 className="admin-page__title">Profile</h1>

      <section className="admin-section">
        <h2 className="admin-section__title">Profile Details</h2>

        {profileError && <div className="admin-error-banner">{profileError}</div>}
        {profileSuccess && <div className="admin-success-banner">Profile saved successfully.</div>}

        <form className="admin-form" onSubmit={handleProfileSubmit}>
          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="name">Name</label>
            <input
              className="admin-form__input"
              id="name"
              name="name"
              onChange={handleProfileChange}
              type="text"
              value={form.name}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="title">Title</label>
            <input
              className="admin-form__input"
              id="title"
              name="title"
              onChange={handleProfileChange}
              type="text"
              value={form.title}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="headline">Headline</label>
            <input
              className="admin-form__input"
              id="headline"
              name="headline"
              onChange={handleProfileChange}
              type="text"
              value={form.headline ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="description">Description</label>
            <textarea
              className="admin-form__textarea"
              id="description"
              name="description"
              onChange={handleProfileChange}
              rows={6}
              value={form.description ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="location">Location</label>
            <input
              className="admin-form__input"
              id="location"
              name="location"
              onChange={handleProfileChange}
              type="text"
              value={form.location ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="phoneNumber">Phone Number</label>
            <input
              className="admin-form__input"
              id="phoneNumber"
              name="phoneNumber"
              onChange={handleProfileChange}
              type="text"
              value={form.phoneNumber ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="primaryEmail">Primary Email</label>
            <input
              className="admin-form__input"
              id="primaryEmail"
              name="primaryEmail"
              onChange={handleProfileChange}
              type="email"
              value={form.primaryEmail ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="secondaryEmail">Secondary Email</label>
            <input
              className="admin-form__input"
              id="secondaryEmail"
              name="secondaryEmail"
              onChange={handleProfileChange}
              type="email"
              value={form.secondaryEmail ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="profileImage">Profile Image URL</label>
            <input
              className="admin-form__input"
              id="profileImage"
              name="profileImage"
              onChange={handleProfileChange}
              type="text"
              value={form.profileImage ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="sidebarImage">Sidebar Image URL</label>
            <input
              className="admin-form__input"
              id="sidebarImage"
              name="sidebarImage"
              onChange={handleProfileChange}
              type="text"
              value={form.sidebarImage ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="backgroundImage">Background Image URL</label>
            <input
              className="admin-form__input"
              id="backgroundImage"
              name="backgroundImage"
              onChange={handleProfileChange}
              type="text"
              value={form.backgroundImage ?? ''}
            />
          </div>

          <div className="admin-form__field">
            <label className="admin-form__label" htmlFor="mobileBackgroundImage">
              Mobile Background Image URL
            </label>
            <input
              className="admin-form__input"
              id="mobileBackgroundImage"
              name="mobileBackgroundImage"
              onChange={handleProfileChange}
              type="text"
              value={form.mobileBackgroundImage ?? ''}
            />
          </div>

          <div className="admin-form__actions">
            <button
              className="admin-btn admin-btn--primary"
              disabled={profileSaving}
              type="submit"
            >
              {profileSaving ? 'Saving...' : 'Save Profile'}
            </button>
          </div>
        </form>
      </section>

      <section className="admin-section">
        <div className="admin-section__header">
          <h2 className="admin-section__title">Social Media Links</h2>
          <button
            className="admin-btn admin-btn--primary"
            onClick={() => setShowAddForm((prev) => !prev)}
            type="button"
          >
            {showAddForm ? 'Cancel' : 'Add Link'}
          </button>
        </div>

        {socialMediaError && <div className="admin-error-banner">{socialMediaError}</div>}

        {showAddForm && (
          <form className="admin-form admin-form--inline" onSubmit={handleAddSubmit}>
            <h3 className="admin-form__subtitle">New Social Media Link</h3>
            <div className="admin-form__row">
              <div className="admin-form__field">
                <label className="admin-form__label" htmlFor="add-type">Type</label>
                <input
                  className="admin-form__input"
                  id="add-type"
                  name="type"
                  onChange={handleAddChange}
                  placeholder="e.g. github"
                  required
                  type="text"
                  value={addForm.type}
                />
              </div>
              <div className="admin-form__field">
                <label className="admin-form__label" htmlFor="add-link">Link</label>
                <input
                  className="admin-form__input"
                  id="add-link"
                  name="link"
                  onChange={handleAddChange}
                  placeholder="https://..."
                  required
                  type="text"
                  value={addForm.link}
                />
              </div>
              <div className="admin-form__field">
                <label className="admin-form__label" htmlFor="add-name">Name</label>
                <input
                  className="admin-form__input"
                  id="add-name"
                  name="name"
                  onChange={handleAddChange}
                  placeholder="Display name"
                  type="text"
                  value={addForm.name}
                />
              </div>
              <div className="admin-form__field admin-form__field--checkbox">
                <label className="admin-form__label admin-form__label--checkbox">
                  <input
                    checked={addForm.includeOnResume}
                    name="includeOnResume"
                    onChange={handleAddChange}
                    type="checkbox"
                  />
                  Include on Resume
                </label>
              </div>
            </div>
            <div className="admin-form__actions">
              <button
                className="admin-btn admin-btn--primary"
                disabled={addSaving}
                type="submit"
              >
                {addSaving ? 'Adding...' : 'Add'}
              </button>
            </div>
          </form>
        )}

        {socialMediaLoading ? (
          <div className="admin-loading">Loading social media links...</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th className="admin-table__th">Type</th>
                <th className="admin-table__th">Link</th>
                <th className="admin-table__th">Name</th>
                <th className="admin-table__th">Resume</th>
                <th className="admin-table__th">Actions</th>
              </tr>
            </thead>
            <tbody>
              {socialMedia.length === 0 && (
                <tr>
                  <td className="admin-table__td admin-table__td--empty" colSpan={5}>
                    No social media links found.
                  </td>
                </tr>
              )}
              {socialMedia.map((item) =>
                editingId === item.id ? (
                  <tr key={item.id} className="admin-table__row admin-table__row--editing">
                    <td className="admin-table__td">
                      <input
                        className="admin-form__input admin-form__input--compact"
                        name="type"
                        onChange={handleEditChange}
                        type="text"
                        value={editForm.type}
                      />
                    </td>
                    <td className="admin-table__td">
                      <input
                        className="admin-form__input admin-form__input--compact"
                        name="link"
                        onChange={handleEditChange}
                        type="text"
                        value={editForm.link}
                      />
                    </td>
                    <td className="admin-table__td">
                      <input
                        className="admin-form__input admin-form__input--compact"
                        name="name"
                        onChange={handleEditChange}
                        type="text"
                        value={editForm.name}
                      />
                    </td>
                    <td className="admin-table__td">
                      <input
                        checked={editForm.includeOnResume}
                        name="includeOnResume"
                        onChange={handleEditChange}
                        type="checkbox"
                      />
                    </td>
                    <td className="admin-table__td admin-table__td--actions">
                      <button
                        className="admin-btn admin-btn--sm admin-btn--primary"
                        disabled={editSaving}
                        onClick={() => handleEditSave(item.id)}
                        type="button"
                      >
                        {editSaving ? 'Saving...' : 'Save'}
                      </button>
                      <button
                        className="admin-btn admin-btn--sm"
                        onClick={handleEditCancel}
                        type="button"
                      >
                        Cancel
                      </button>
                    </td>
                  </tr>
                ) : (
                  <tr key={item.id} className="admin-table__row">
                    <td className="admin-table__td">{item.type}</td>
                    <td className="admin-table__td">{item.link}</td>
                    <td className="admin-table__td">{item.name ?? '-'}</td>
                    <td className="admin-table__td">{item.includeOnResume ? 'Yes' : 'No'}</td>
                    <td className="admin-table__td admin-table__td--actions">
                      <button
                        className="admin-btn admin-btn--sm"
                        onClick={() => handleEditStart(item)}
                        type="button"
                      >
                        Edit
                      </button>
                      <button
                        className="admin-btn admin-btn--sm admin-btn--danger"
                        onClick={() =>
                          setDeleteTarget({ id: item.id, name: item.type })
                        }
                        type="button"
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        )}
      </section>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="Delete Social Media Link"
        message={`Are you sure you want to delete the "${deleteTarget?.name}" link? This action cannot be undone.`}
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  )
}
