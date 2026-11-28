import { describe, expect, it } from 'vitest';

import type { FamilyDocument, SchoolOffer } from '../../types/documents';

import {
  addMonths,
  attentionItems,
  expiringSoon,
  filesProblem,
  groupBySection,
  matchesChild,
  matchesSearch,
  safeReportLink,
  untilText,
  type DocChild,
} from './documentText';

const children: DocChild[] = [
  { id: 'c1', firstName: 'Mia', tone: 0 },
  { id: 'c2', firstName: 'Leo', tone: 1 },
];

const doc = (overrides: Partial<FamilyDocument>): FamilyDocument => ({
  id: 'd',
  familyId: 'f',
  childIds: [],
  section: 'OTHER',
  title: 'Document',
  datedOn: '2026-01-01',
  dueOn: null,
  dueKind: null,
  note: null,
  versions: [{ versionId: 'v1', files: [{ fileId: 'f1', mimeType: 'image/jpeg', sizeBytes: 1 }], datedOn: '2026-01-01', dueOn: null, dueKind: null, uploadedBy: 'p1', uploadedAt: '2026-01-02T10:00:00Z' }],
  source: { kind: 'UPLOAD', schoolDocumentId: null },
  archivedAt: null,
  archivedBy: null,
  createdBy: 'p1',
  createdAt: '2026-01-02T10:00:00Z',
  updatedAt: '2026-01-02T10:00:00Z',
  version: 1,
  ...overrides,
});

const TODAY = '2026-11-28';

describe('documentText', () => {
  it('adds calendar months, clamping to a shorter month', () => {
    expect(addMonths('2026-08-31', 6)).toBe('2027-02-28');
    expect(addMonths('2026-11-28', 2)).toBe('2027-01-28');
  });

  it('says how long is left in words', () => {
    expect(untilText('2027-03-14', TODAY)).toBe('in 3 months');
    expect(untilText('2026-12-08', TODAY)).toBe('in 10 days');
    expect(untilText('2026-11-01', TODAY)).toBe('on 1 Nov 2026');
  });

  it('lists school letters first, then expiries within 6 months and reviews within 2, soonest first', () => {
    const passport = doc({ id: 'p', childIds: ['c1'], title: 'Passport', section: 'IDENTITY', dueOn: '2027-03-14', dueKind: 'EXPIRES' });
    const farPassport = doc({ id: 'p2', childIds: ['c2'], title: 'Passport', dueOn: '2030-08-02', dueKind: 'EXPIRES' });
    const plan = doc({ id: 'a', childIds: ['c2'], title: 'Asthma care plan', dueOn: '2027-01-10', dueKind: 'REVIEW' });
    const laterReview = doc({ id: 'r', title: 'Plan', dueOn: '2027-03-01', dueKind: 'REVIEW' });
    const offers = [{ id: 'o1' }, { id: 'o2' }] as SchoolOffer[];

    const items = attentionItems([passport, farPassport, plan, laterReview], offers, children, TODAY);

    expect(items.map((item) => item.title)).toEqual([
      '2 letters from school to file',
      "Leo's asthma care plan is due for review",
      "Mia's passport expires in 3 months",
    ]);
    expect(items[0].detail).toBe('Choose which to keep for Mia and Leo');
  });

  it('names a whole-family document as the family', () => {
    const order = doc({ title: 'Court order', dueOn: '2027-01-01', dueKind: 'EXPIRES' });
    expect(attentionItems([order], [], children, TODAY)[0].title).toBe("The family's court order expires in 1 month");
  });

  it('only warns the dashboard about expiries within 3 months, never reviews or archived ones', () => {
    const soon = doc({ id: 'soon', dueOn: '2027-02-01', dueKind: 'EXPIRES' });
    const later = doc({ id: 'later', dueOn: '2027-04-01', dueKind: 'EXPIRES' });
    const review = doc({ id: 'review', dueOn: '2026-12-10', dueKind: 'REVIEW' });
    const archived = doc({ id: 'archived', dueOn: '2026-12-10', dueKind: 'EXPIRES', archivedAt: '2026-11-01T00:00:00Z' });
    expect(expiringSoon([later, soon, review, archived], TODAY).map((entry) => entry.id)).toEqual(['soon']);
  });

  it('groups by section in the fixed order, newest dated first', () => {
    const groups = groupBySection([
      doc({ id: 'old', section: 'HEALTH', datedOn: '2019-01-01' }),
      doc({ id: 'id', section: 'IDENTITY' }),
      doc({ id: 'new', section: 'HEALTH', datedOn: '2026-10-02' }),
    ]);
    expect(groups.map((group) => group.section.key)).toEqual(['IDENTITY', 'HEALTH']);
    expect(groups[1].documents.map((entry) => entry.id)).toEqual(['new', 'old']);
  });

  it('filters by child or family, and searches title, child and section', () => {
    const mia = doc({ childIds: ['c1'], title: 'Passport', section: 'IDENTITY' });
    const family = doc({ title: 'Parenting plan', section: 'AGREEMENTS' });
    expect(matchesChild(mia, 'c1')).toBe(true);
    expect(matchesChild(mia, 'family')).toBe(false);
    expect(matchesChild(family, 'family')).toBe(true);
    expect(matchesSearch(mia, 'mia pass', children)).toBe(true);
    expect(matchesSearch(mia, 'travel', children)).toBe(true);
    expect(matchesSearch(family, 'whole family', children)).toBe(true);
    expect(matchesSearch(mia, 'leo', children)).toBe(false);
  });

  it('accepts a report link only when its parsed origin is Insight Tracking', () => {
    expect(safeReportLink('https://app.insighttracking.com/report/1')).toBe('https://app.insighttracking.com/report/1');
    expect(safeReportLink('https://app.insighttracking.com.attacker.example/phish')).toBeNull();
    expect(safeReportLink('http://app.insighttracking.com/report/1')).toBeNull();
    expect(safeReportLink('https://evil.example/?https://app.insighttracking.com')).toBeNull();
    expect(safeReportLink('javascript:alert(1)')).toBeNull();
    expect(safeReportLink('not a url')).toBeNull();
    expect(safeReportLink(null)).toBeNull();
  });

  it('refuses files the API would refuse', () => {
    const file = (name: string, type: string, size = 10) => {
      const made = new File(['x'], name, { type });
      Object.defineProperty(made, 'size', { value: size });
      return made;
    };
    expect(filesProblem([])).toMatch(/Add the photos/);
    expect(filesProblem([file('a.gif', 'image/gif')])).toMatch(/not a JPEG/);
    expect(filesProblem([file('a.pdf', 'application/pdf', 21 * 1024 * 1024)])).toMatch(/over 20 MB/);
    expect(filesProblem(Array.from({ length: 13 }, (_, index) => file(`${index}.jpg`, 'image/jpeg')))).toMatch(/Up to 12/);
    expect(filesProblem([file('a.jpg', 'image/jpeg'), file('b.pdf', 'application/pdf')])).toBeNull();
  });
});
