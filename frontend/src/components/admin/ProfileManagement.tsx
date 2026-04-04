import { useState } from 'react'
import { API_BASE_URL } from '../../config/api'
import { useProfile } from '../../hooks/useProfile'

export function ProfileManagement() {
  const { profile } = useProfile()
  const [bio, setBio] = useState(profile?.description ?? '')

  return (
    <div className="profile-mgmt card">
      <h3 className="profile-mgmt__title">Profile Management</h3>
      <div className="profile-mgmt__layout">
        <div className="profile-mgmt__fields">
          <div className="profile-mgmt__field">
            <label className="profile-mgmt__label">Short Biography</label>
            <textarea
              className="profile-mgmt__bio"
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              rows={3}
            />
          </div>
          <div className="profile-mgmt__uploads">
            <div className="profile-mgmt__upload-field">
              <label className="profile-mgmt__label">Primary CV Link</label>
              <input
                type="text"
                className="profile-mgmt__input"
                defaultValue={profile?.cvUrl ?? ''}
                readOnly
              />
            </div>
            <div className="profile-mgmt__upload-field">
              <label className="profile-mgmt__label">Technical Portfolio PDF</label>
              <input
                type="text"
                className="profile-mgmt__input"
                placeholder="Upload portfolio..."
                readOnly
              />
            </div>
          </div>
          <button type="button" className="button button--primary profile-mgmt__save">
            Save Profile Changes
          </button>
        </div>
        <div className="profile-mgmt__image-col">
          {profile?.profileImage?.url && (
            <div className="profile-mgmt__image-wrapper">
              <img
                src={`${API_BASE_URL}${profile.profileImage.url}`}
                alt="Profile"
                className="profile-mgmt__image"
              />
              <span className="profile-mgmt__image-label">CHANGE IMAGE</span>
            </div>
          )}
          <p className="profile-mgmt__image-hint">
            Recommended: Professional headshot, 800x800px minimum
          </p>
        </div>
      </div>
    </div>
  )
}
