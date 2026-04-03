import { Eye, Trash2 } from 'lucide-react'

interface Inquiry {
  id: string
  sender: string
  subject: string
  type: string
  received: string
  status: 'new' | 'read' | 'replied'
}

const placeholderInquiries: Inquiry[] = [
  { id: '1', sender: 'Emily Vance', subject: 'Partnership Proposal: Secure N...', type: 'Partnership', received: '2h ago', status: 'new' },
  { id: '2', sender: 'Marcus Chen', subject: 'Question about Digital Architec...', type: 'General', received: '5h ago', status: 'read' },
  { id: '3', sender: 'Sarah Jenkins', subject: 'Freelance Inquiry — Frontend G...', type: 'Freelance', received: '1d ago', status: 'replied' },
]

const statusClasses: Record<Inquiry['status'], string> = {
  new: 'inquiry-list__status--new',
  read: 'inquiry-list__status--read',
  replied: 'inquiry-list__status--replied',
}

export function InquiryList() {
  return (
    <div className="inquiry-list card">
      <div className="inquiry-list__header">
        <h3 className="inquiry-list__title">Recent Inquiries</h3>
        <a href="#" className="inquiry-list__view-all">View All →</a>
      </div>
      <table className="inquiry-list__table">
        <thead>
          <tr>
            <th>Sender</th>
            <th>Subject</th>
            <th>Type</th>
            <th>Received</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {placeholderInquiries.map((inq) => (
            <tr key={inq.id} className="inquiry-list__row">
              <td>{inq.sender}</td>
              <td>{inq.subject}</td>
              <td>{inq.type}</td>
              <td>{inq.received}</td>
              <td>
                <span className={`inquiry-list__status ${statusClasses[inq.status]}`}>
                  {inq.status}
                </span>
              </td>
              <td className="inquiry-list__actions">
                <button type="button" aria-label="View"><Eye size={14} /></button>
                <button type="button" aria-label="Delete"><Trash2 size={14} /></button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
