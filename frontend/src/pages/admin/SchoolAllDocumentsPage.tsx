import { SchoolDocumentsAdmin } from './SchoolDocumentsAdmin'

/** Everything ingested, whatever its tier or status. */
export function SchoolAllDocumentsPage() {
  return (
    <SchoolDocumentsAdmin
      heading="Term Time documents"
      subtitle="Everything ingested from the school website, its PDFs and the school mailbox."
    />
  )
}
