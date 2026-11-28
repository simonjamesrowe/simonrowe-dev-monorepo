// The wire shapes of the documents library (specs/057-coparent-documents/contracts.md). Ids are
// strings, dates YYYY-MM-DD, instants ISO-8601 UTC.

export type DocumentSection = 'IDENTITY' | 'HEALTH' | 'SCHOOL' | 'AGREEMENTS' | 'OTHER';
export type DueKind = 'EXPIRES' | 'REVIEW';
export type DocumentSourceKind = 'UPLOAD' | 'QUICK_ADD' | 'SCHOOL_LETTER' | 'SCHOOL_REPORT';

export interface DocumentFile {
  fileId: string;
  mimeType: string;
  sizeBytes: number;
}

export interface DocumentVersion {
  versionId: string;
  /** Photos of one document are its pages, in order. */
  files: DocumentFile[];
  datedOn: string | null;
  dueOn: string | null;
  dueKind: DueKind | null;
  uploadedBy: string;
  uploadedAt: string;
}

export interface FamilyDocument {
  id: string;
  familyId: string;
  /** Empty means the whole family. */
  childIds: string[];
  section: DocumentSection;
  title: string;
  datedOn: string | null;
  dueOn: string | null;
  dueKind: DueKind | null;
  note: string | null;
  /** Oldest first, newest last, never empty. */
  versions: DocumentVersion[];
  source: { kind: DocumentSourceKind; schoolDocumentId: string | null };
  archivedAt: string | null;
  archivedBy: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export type DocumentHistoryAction = 'CREATED' | 'EDITED' | 'NEW_VERSION' | 'ARCHIVED' | 'RESTORED';

export interface DocumentHistoryItem {
  action: DocumentHistoryAction;
  parentId: string;
  at: string;
}

export interface DocumentDetail extends FamilyDocument {
  history: DocumentHistoryItem[];
}

/** What create and edit send. */
export interface DocumentDetails {
  childIds: string[];
  section: DocumentSection;
  title: string;
  datedOn: string | null;
  dueOn: string | null;
  dueKind: DueKind | null;
  note: string | null;
  /** Create only: the report prompt this document fulfils. */
  schoolOfferId?: string | null;
}

export interface VersionDetails {
  datedOn: string | null;
  dueOn: string | null;
  dueKind: DueKind | null;
}

/** A suggestion for the form from reading the upload; never saved. Every field may be null. */
export interface DocumentProposal {
  childIds: string[] | null;
  section: DocumentSection | null;
  title: string | null;
  datedOn: string | null;
  dueOn: string | null;
  dueKind: DueKind | null;
  confidence: number | null;
}

export interface SchoolOffer {
  id: string;
  kind: 'LETTER' | 'REPORT_READY';
  childIds: string[];
  section: DocumentSection;
  title: string;
  sender: string;
  sentAt: string;
  personal: boolean;
  reason: string;
  /** LETTER only. */
  attachmentName: string | null;
  /** REPORT_READY only: an Insight Tracking address. */
  reportLink: string | null;
  suggestedTitle: string | null;
  suggestedDatedOn: string | null;
}
