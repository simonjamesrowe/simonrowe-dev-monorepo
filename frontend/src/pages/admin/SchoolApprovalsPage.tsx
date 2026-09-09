import { SchoolDocumentsAdmin } from './SchoolDocumentsAdmin'

/** The documents table pre-filtered to what is waiting for a decision. */
export function SchoolApprovalsPage() {
  return (
    <SchoolDocumentsAdmin
      fixedStatus="awaiting"
      heading="Term Time approvals"
      subtitle="Proposed for the public tier. Nothing here is publicly readable until you approve it."
    />
  )
}
