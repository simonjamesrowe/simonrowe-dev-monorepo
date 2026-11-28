// The documents library's rules with no I/O: section names and order, what needs attention,
// filtering, grouping and the wording the page and drawers share.

import type { DocumentSection, DueKind, FamilyDocument, SchoolOffer } from '../../types/documents';

export interface SectionInfo {
  key: DocumentSection;
  label: string;
  hint: string;
}

/** The fixed sections, in the order the library lists them. */
export const SECTIONS: SectionInfo[] = [
  { key: 'IDENTITY', label: 'Identity and travel', hint: 'Passports, birth certificates, travel consent letters' },
  { key: 'HEALTH', label: 'Health', hint: 'Red book, vaccinations, care plans, hospital and GP letters' },
  { key: 'SCHOOL', label: 'School', hint: 'Reports, letters, consent forms, assessments' },
  { key: 'AGREEMENTS', label: 'Agreements', hint: 'Parenting plan, court orders, mediation summaries' },
  { key: 'OTHER', label: 'Activities and other', hint: 'Certificates, club forms, anything else' },
];

export const sectionLabel = (section: DocumentSection | null | undefined): string =>
  SECTIONS.find((entry) => entry.key === section)?.label ?? 'Activities and other';

export const isSection = (value: unknown): value is DocumentSection =>
  SECTIONS.some((entry) => entry.key === value);

/** A child as the library shows them: first name and the colour that marks them app-wide. */
export interface DocChild {
  id: string;
  firstName: string;
  tone: number;
}

export const ymdToday = (now: Date): string => {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
};

/** A YYYY-MM-DD date at local midday, so a clock change can never move it. */
const atNoon = (ymd: string) => new Date(`${ymd.slice(0, 10)}T12:00:00`);

/** `n` calendar months after a date, clamped to the end of a shorter month. */
export function addMonths(ymd: string, months: number): string {
  const date = atNoon(ymd);
  const day = date.getDate();
  date.setDate(1);
  date.setMonth(date.getMonth() + months);
  const last = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
  date.setDate(Math.min(day, last));
  return ymdToday(date);
}

export const formatDay = (ymd: string | null | undefined): string => {
  if (!ymd) return '';
  const date = atNoon(ymd);
  return Number.isNaN(date.getTime())
    ? ymd
    : date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
};

export const formatInstant = (iso: string | null | undefined): string => {
  if (!iso) return '';
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
};

/** Whether `due` falls on or before `months` months from today (an overdue date counts). */
export const dueWithin = (due: string | null, today: string, months: number): boolean =>
  !!due && due <= addMonths(today, months);

/** "in 4 months", "in 12 days", "today", or "on 3 Mar 2026" for a date already gone. */
export function untilText(due: string, today: string): string {
  const days = Math.round((atNoon(due).getTime() - atNoon(today).getTime()) / 86_400_000);
  if (days < 0) return `on ${formatDay(due)}`;
  if (days === 0) return 'today';
  if (days < 31) return `in ${days} day${days === 1 ? '' : 's'}`;
  const months = Math.round(days / 30.44);
  return `in ${months} month${months === 1 ? '' : 's'}`;
}

/** "Mia", "Mia and Leo", "Mia, Leo and Zoe". */
export const joinNames = (names: string[]): string => {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
};

export const childrenOf = (ids: string[], children: DocChild[]): DocChild[] =>
  ids.flatMap((id) => children.find((child) => child.id === id) ?? []);

/** Who a document is for, in words: "Mia", "Mia and Leo", or "Whole family". */
export const forText = (ids: string[], children: DocChild[]): string => {
  const names = childrenOf(ids, children).map((child) => child.firstName);
  return names.length ? joinNames(names) : 'Whole family';
};

/** The possessive used in sentences: "Mia's", "Mia and Leo's", "The family's". */
export const possessive = (ids: string[], children: DocChild[], capital = true): string => {
  const names = childrenOf(ids, children).map((child) => child.firstName);
  if (!names.length) return capital ? "The family's" : "the family's";
  return `${joinNames(names)}'s`;
};

/** A title for the middle of a sentence: "Passport" becomes "passport", "SATs results" stays. */
export const inSentence = (title: string): string =>
  /^[A-Z][a-z]/.test(title) ? title.charAt(0).toLowerCase() + title.slice(1) : title;

export const currentVersion = (document: FamilyDocument) =>
  document.versions[document.versions.length - 1];

export const fromSchool = (document: FamilyDocument) =>
  document.source.kind === 'SCHOOL_LETTER' || document.source.kind === 'SCHOOL_REPORT';

export type ChildFilter = 'all' | 'family' | string;

export const matchesChild = (document: FamilyDocument, filter: ChildFilter): boolean => {
  if (filter === 'all') return true;
  if (filter === 'family') return document.childIds.length === 0;
  return document.childIds.includes(filter);
};

/** Free-text search over title, who it is for and the section, ignoring case. */
export const matchesSearch = (document: FamilyDocument, query: string, children: DocChild[]): boolean => {
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  const haystack = [
    document.title,
    forText(document.childIds, children),
    sectionLabel(document.section),
  ].join(' ').toLowerCase();
  return needle.split(/\s+/).every((word) => haystack.includes(word));
};

/** The library's groups: sections in their fixed order, newest dated first within each. */
export function groupBySection(documents: FamilyDocument[]) {
  return SECTIONS.map((section) => ({
    section,
    documents: documents
      .filter((document) => (isSection(document.section) ? document.section : 'OTHER') === section.key)
      .sort((left, right) => (right.datedOn ?? '').localeCompare(left.datedOn ?? '')),
  })).filter((group) => group.documents.length > 0);
}

export const EXPIRY_WARN_MONTHS = 6;
export const REVIEW_WARN_MONTHS = 2;
export const BRIEFING_EXPIRY_MONTHS = 3;

export interface AttentionItem {
  key: string;
  kind: 'school' | 'due';
  documentId?: string;
  title: string;
  detail: string;
}

const dueKindOf = (document: FamilyDocument): DueKind | null => (document.dueOn ? document.dueKind : null);

/** One line about a document's coming date, for the strip and the dashboard. */
export function dueLine(document: FamilyDocument, children: DocChild[], today: string): { title: string; detail: string } {
  const whose = possessive(document.childIds, children);
  const what = inSentence(document.title);
  const due = document.dueOn ?? today;
  const past = due < today;
  if (dueKindOf(document) === 'REVIEW') {
    return {
      title: past ? `${whose} ${what} was due for review` : `${whose} ${what} is due for review`,
      detail: `Review by ${formatDay(due)}`,
    };
  }
  return {
    title: past ? `${whose} ${what} expired ${untilText(due, today)}` : `${whose} ${what} expires ${untilText(due, today)}`,
    detail: past ? 'Renew it, then upload the new one here.' : `${formatDay(due)}. You both get email reminders.`,
  };
}

/**
 * What the needs-attention strip lists: letters waiting in From school first, then anything
 * expiring within 6 months or due for review within 2, soonest first.
 */
export function attentionItems(
  documents: FamilyDocument[],
  offers: SchoolOffer[],
  children: DocChild[],
  today: string,
): AttentionItem[] {
  const due = documents
    .filter((document) => !document.archivedAt)
    .filter((document) =>
      dueKindOf(document) === 'REVIEW'
        ? dueWithin(document.dueOn, today, REVIEW_WARN_MONTHS)
        : dueKindOf(document) === 'EXPIRES' && dueWithin(document.dueOn, today, EXPIRY_WARN_MONTHS))
    .sort((left, right) => (left.dueOn ?? '').localeCompare(right.dueOn ?? ''))
    .map((document): AttentionItem => ({
      key: `due-${document.id}`,
      kind: 'due',
      documentId: document.id,
      ...dueLine(document, children, today),
    }));
  if (!offers.length) return due;
  const names = joinNames(children.map((child) => child.firstName));
  return [
    {
      key: 'school',
      kind: 'school',
      title: `${offers.length} ${offers.length === 1 ? 'letter' : 'letters'} from school to file`,
      detail: names ? `Choose which to keep for ${names}` : 'Choose which to keep',
    },
    ...due,
  ];
}

/** Documents expiring within 3 months, soonest first, for the dashboard briefing. */
export const expiringSoon = (documents: FamilyDocument[], today: string): FamilyDocument[] =>
  documents
    .filter((document) => !document.archivedAt && dueKindOf(document) === 'EXPIRES')
    .filter((document) => dueWithin(document.dueOn, today, BRIEFING_EXPIRY_MONTHS))
    .sort((left, right) => (left.dueOn ?? '').localeCompare(right.dueOn ?? ''));

const INSIGHT_TRACKING_ORIGIN = 'https://app.insighttracking.com';

/**
 * The report link, only when its parsed origin is exactly Insight Tracking's. Compared by origin,
 * never by prefix: `https://app.insighttracking.com.attacker.example/` starts with the right text.
 */
export function safeReportLink(link: string | null | undefined): string | null {
  if (!link) return null;
  try {
    const url = new URL(link);
    return url.origin === INSIGHT_TRACKING_ORIGIN ? url.href : null;
  } catch {
    return null;
  }
}

const EXTENSIONS: Record<string, string> = {
  'application/pdf': 'pdf',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
};

/** A download name built from the title, since the API keeps no original file name. */
export function downloadName(title: string, mimeType: string, page?: number): string {
  const safe = title.replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim() || 'document';
  return `${safe}${page ? ` (page ${page})` : ''}.${EXTENSIONS[mimeType] ?? 'bin'}`;
}

export const isPdf = (mimeType: string) => mimeType === 'application/pdf';

/** What the upload controls accept, matching the API's sniffed types. */
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];
export const MAX_FILE_BYTES = 20 * 1024 * 1024;
export const MAX_FILES = 12;

/** Why a set of files cannot be uploaded, or null when it can. */
export function filesProblem(files: File[]): string | null {
  if (files.length === 0) return 'Add the photos or PDF of the document.';
  if (files.length > MAX_FILES) return `Up to ${MAX_FILES} files for one document.`;
  const wrongType = files.find((file) => file.type && !ACCEPTED_TYPES.includes(file.type));
  if (wrongType) return `${wrongType.name} is not a JPEG, PNG, WebP or PDF.`;
  const tooBig = files.find((file) => file.size > MAX_FILE_BYTES);
  if (tooBig) return `${tooBig.name} is over 20 MB.`;
  return null;
}
