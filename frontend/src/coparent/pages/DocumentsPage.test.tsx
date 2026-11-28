import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CONFLICT_MESSAGE } from '../components/documents/documentErrors';
import { ToastProvider } from '../components/ui/ToastProvider';
import * as apiHooks from '../hooks/api';
import * as documentHooks from '../hooks/api/useDocuments';
import type { FamilyDocument, SchoolOffer } from '../types/documents';

import DocumentsPage from './DocumentsPage';

vi.mock('../hooks/api', () => ({
  useFamilies: vi.fn(),
  useCurrentParentId: vi.fn(),
  useParentsWithInvited: vi.fn(),
  useChildren: vi.fn(),
}));

vi.mock('../hooks/api/useDocuments', () => ({
  useDocuments: vi.fn(),
  useDocument: vi.fn(),
  useSchoolOffers: vi.fn(),
  useCreateDocument: vi.fn(),
  useUpdateDocument: vi.fn(),
  useAddDocumentVersion: vi.fn(),
  useArchiveDocument: vi.fn(),
  useReadDocument: vi.fn(),
  useKeepSchoolOffer: vi.fn(),
  useDismissSchoolOffer: vi.fn(),
  fetchDocumentFile: vi.fn(),
}));

const query = <T,>(data: T) => ({ data, isLoading: false, isError: false }) as never;
const mutation = (mutateAsync: ReturnType<typeof vi.fn>) => ({ mutateAsync, isPending: false }) as never;

const alex = { id: 'alex', familyId: 'fam', fullName: 'Alex Morgan', role: 'primary' as const, status: 'active' };
const sam = { id: 'sam', familyId: 'fam', fullName: 'Sam Taylor', role: 'co-parent' as const, status: 'active' };
const kids = [
  { id: 'c1', familyId: 'fam', fullName: 'Mia Morgan-Taylor', dateOfBirth: '2015-06-20' },
  { id: 'c2', familyId: 'fam', fullName: 'Leo Morgan-Taylor', dateOfBirth: '2019-04-11' },
];

const version = (id: string, overrides: Partial<FamilyDocument['versions'][number]> = {}) => ({
  versionId: id,
  files: [{ fileId: `${id}-f1`, mimeType: 'image/jpeg', sizeBytes: 100 }],
  datedOn: '2025-08-02',
  dueOn: null,
  dueKind: null,
  uploadedBy: 'alex',
  uploadedAt: '2026-11-02T10:00:00Z',
  ...overrides,
});

const doc = (overrides: Partial<FamilyDocument>): FamilyDocument => ({
  id: 'd',
  familyId: 'fam',
  childIds: [],
  section: 'OTHER',
  title: 'Document',
  datedOn: '2026-01-01',
  dueOn: null,
  dueKind: null,
  note: null,
  versions: [version('v1')],
  source: { kind: 'UPLOAD', schoolDocumentId: null },
  archivedAt: null,
  archivedBy: null,
  createdBy: 'alex',
  createdAt: '2026-11-02T10:00:00Z',
  updatedAt: '2026-11-02T10:00:00Z',
  version: 3,
  ...overrides,
});

const miaPassport = doc({ id: 'd1', childIds: ['c1'], section: 'IDENTITY', title: 'Passport', datedOn: '2022-03-14', dueOn: '2027-03-14', dueKind: 'EXPIRES' });
const leoPassport = doc({
  id: 'd2', childIds: ['c2'], section: 'IDENTITY', title: 'Passport', datedOn: '2025-08-02', dueOn: '2030-08-02', dueKind: 'EXPIRES',
  versions: [version('v0', { datedOn: '2020-08-01', dueOn: '2025-08-01', dueKind: 'EXPIRES', uploadedAt: '2024-01-15T10:00:00Z' }), version('v1', { dueOn: '2030-08-02', dueKind: 'EXPIRES' })],
});
const asthma = doc({ id: 'd5', childIds: ['c2'], section: 'HEALTH', title: 'Asthma care plan', datedOn: '2026-01-10', dueOn: '2027-01-10', dueKind: 'REVIEW' });
const order = doc({ id: 'd10', section: 'AGREEMENTS', title: 'Child arrangements order', datedOn: '2024-05-09' });
const report = doc({ id: 'd8', childIds: ['c1'], section: 'SCHOOL', title: 'Year 5 end-of-year report', datedOn: '2026-07-17', source: { kind: 'SCHOOL_LETTER', schoolDocumentId: 'sd1' } });
const archivedRedBook = doc({
  id: 'd13', childIds: ['c2'], section: 'HEALTH', title: 'Red book duplicate scan', archivedAt: '2026-11-05T09:00:00Z', archivedBy: 'sam',
});

const letter: SchoolOffer = {
  id: 's1', kind: 'LETTER', childIds: ['c1'], section: 'SCHOOL', title: 'Year 6 residential: consent form',
  sender: 'Kilmorie Primary School', sentAt: '2026-11-26T09:00:00Z', personal: false,
  reason: 'Sent to Year 6 parents. Mia is in Year 6.', attachmentName: 'Kingswood-consent.pdf',
  reportLink: null, suggestedTitle: null, suggestedDatedOn: null,
};
const personal: SchoolOffer = { ...letter, id: 's2', title: 'Instrumental lessons report: Mia', personal: true, attachmentName: 'Instrumental-report-Mia.pdf' };
const reportPrompt: SchoolOffer = {
  id: 's4', kind: 'REPORT_READY', childIds: ['c2'], section: 'SCHOOL', title: "Leo's report is ready on Insight Tracking",
  sender: 'Kilmorie Primary School (Insight Tracking)', sentAt: '2026-11-24T09:00:00Z', personal: false,
  reason: 'The school publishes reports on Insight Tracking.', attachmentName: null,
  reportLink: 'https://app.insighttracking.com/parent/report/123', suggestedTitle: 'Autumn report 2026/27', suggestedDatedOn: '2026-11-24',
};
const forgedPrompt: SchoolOffer = {
  ...reportPrompt, id: 's5', title: "Mia's report is ready on Insight Tracking", childIds: ['c1'],
  reportLink: 'https://app.insighttracking.com.attacker.example/phish',
};

const create = vi.fn();
const update = vi.fn();
const addVersion = vi.fn();
const archive = vi.fn();
const read = vi.fn();
const keep = vi.fn();
const dismiss = vi.fn();

let current: FamilyDocument[];
let archivedList: FamilyDocument[];
let offers: SchoolOffer[];

const renderPage = (path = '/documents') =>
  render(
    <ToastProvider>
      <MemoryRouter initialEntries={[path]}>
        <DocumentsPage />
      </MemoryRouter>
    </ToastProvider>,
  );

const jpeg = (name = 'passport.jpg') => new File(['jpeg'], name, { type: 'image/jpeg' });

describe('DocumentsPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 28, 10, 0));
    URL.createObjectURL = vi.fn(() => 'blob:preview');
    URL.revokeObjectURL = vi.fn();
    current = [miaPassport, leoPassport, asthma, order, report];
    archivedList = [archivedRedBook];
    offers = [letter, personal, reportPrompt, forgedPrompt];
    [create, update, addVersion, archive, read, keep, dismiss].forEach((fn) => fn.mockReset());
    create.mockResolvedValue(miaPassport);
    update.mockResolvedValue(miaPassport);
    addVersion.mockResolvedValue(miaPassport);
    archive.mockResolvedValue(miaPassport);
    keep.mockResolvedValue(report);
    dismiss.mockResolvedValue('s1');

    vi.mocked(apiHooks.useFamilies).mockReturnValue(query([{ id: 'fam', name: 'Family' }]));
    vi.mocked(apiHooks.useCurrentParentId).mockReturnValue('alex');
    vi.mocked(apiHooks.useParentsWithInvited).mockReturnValue(query([alex, sam]));
    vi.mocked(apiHooks.useChildren).mockReturnValue(query(kids));
    vi.mocked(documentHooks.useDocuments).mockImplementation(
      (_familyId, archived) => query(archived ? archivedList : current),
    );
    vi.mocked(documentHooks.useDocument).mockReturnValue(query(undefined));
    vi.mocked(documentHooks.useSchoolOffers).mockImplementation(() => query(offers));
    vi.mocked(documentHooks.useCreateDocument).mockReturnValue(mutation(create));
    vi.mocked(documentHooks.useUpdateDocument).mockReturnValue(mutation(update));
    vi.mocked(documentHooks.useAddDocumentVersion).mockReturnValue(mutation(addVersion));
    vi.mocked(documentHooks.useArchiveDocument).mockReturnValue(mutation(archive));
    vi.mocked(documentHooks.useReadDocument).mockReturnValue(mutation(read));
    vi.mocked(documentHooks.useKeepSchoolOffer).mockReturnValue(mutation(keep));
    vi.mocked(documentHooks.useDismissSchoolOffer).mockReturnValue(mutation(dismiss));
    vi.mocked(documentHooks.fetchDocumentFile).mockResolvedValue(new Blob(['jpeg'], { type: 'image/jpeg' }));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('groups documents by section and lists what needs attention', () => {
    renderPage();

    expect(screen.getByRole('heading', { name: 'Documents' })).toBeInTheDocument();
    const headings = screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent);
    expect(headings).toEqual([' Identity and travel 2', ' Health 1', ' School 1', ' Agreements 1']);

    const attention = within(screen.getByRole('list', { name: 'Needs attention' }));
    const items = attention.getAllByRole('button').map((button) => button.textContent);
    expect(items[0]).toContain('4 letters from school to file');
    expect(items[1]).toContain("Leo's asthma care plan is due for review");
    expect(items[2]).toContain("Mia's passport expires in 3 months");

    const identity = within(screen.getByRole('region', { name: /Identity and travel/ }));
    expect(identity.getAllByRole('button').map((card) => card.textContent)).toEqual([
      expect.stringContaining('Leo · 2 Aug 2025Valid to 2 Aug 20302 versions'),
      expect.stringContaining('Mia · 14 Mar 2022Expires 14 Mar 2027'),
    ]);
    expect(screen.getByRole('region', { name: /School/ })).toHaveTextContent('From school');
  });

  it('filters by child and family with counts, and searches', async () => {
    const user = userEvent.setup();
    renderPage();

    const chips = within(screen.getByRole('group', { name: 'Show documents for' }));
    expect(chips.getByRole('button', { name: /Everyone 5/ })).toHaveAttribute('aria-pressed', 'true');
    expect(chips.getByRole('button', { name: /Mia 2/ })).toBeInTheDocument();
    expect(chips.getByRole('button', { name: /Leo 2/ })).toBeInTheDocument();
    expect(chips.getByRole('button', { name: /Family 1/ })).toBeInTheDocument();

    await user.click(chips.getByRole('button', { name: /Leo 2/ }));
    expect(screen.queryByText('Child arrangements order')).not.toBeInTheDocument();
    expect(screen.getByText('Asthma care plan')).toBeInTheDocument();

    await user.click(chips.getByRole('button', { name: /Family 1/ }));
    expect(screen.getByText('Child arrangements order')).toBeInTheDocument();
    expect(screen.queryByText('Asthma care plan')).not.toBeInTheDocument();

    await user.click(chips.getByRole('button', { name: /Everyone/ }));
    await user.type(screen.getByRole('searchbox', { name: 'Search documents' }), 'mia passport');
    expect(screen.getAllByText('Passport')).toHaveLength(1);
    expect(screen.queryByText('Asthma care plan')).not.toBeInTheDocument();

    await user.clear(screen.getByRole('searchbox', { name: 'Search documents' }));
    await user.type(screen.getByRole('searchbox', { name: 'Search documents' }), 'nothing like this');
    expect(screen.getByText('Nothing matches.')).toBeInTheDocument();
  });

  it('shows the archived documents under their own chip, without the attention strip', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /Archived 1/ }));

    expect(screen.getByText(/hidden from the library but never deleted/)).toBeInTheDocument();
    expect(screen.getByText('Red book duplicate scan')).toBeInTheDocument();
    expect(screen.queryByText('Passport')).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Needs attention' })).not.toBeInTheDocument();
  });

  it('shows the first-visit empty state when the family has no documents', () => {
    current = [];
    archivedList = [];
    offers = [];
    renderPage();

    expect(screen.getByRole('heading', { name: 'Start with the papers you reach for most' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Photograph a document' })).toBeInTheDocument();
    expect(screen.getByText('Identity and travel')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^From school/ })).not.toBeInTheDocument();
  });

  it('opens a deep-linked document with its versions and actions, and archives it at its version', async () => {
    const user = userEvent.setup();
    vi.mocked(documentHooks.useDocument).mockReturnValue(query({
      ...leoPassport,
      history: [
        { action: 'CREATED', parentId: 'alex', at: '2024-01-15T10:00:00Z' },
        { action: 'NEW_VERSION', parentId: 'sam', at: '2026-11-02T10:00:00Z' },
      ],
    }));
    renderPage('/documents?doc=d2');

    const drawer = within(await screen.findByRole('dialog'));
    expect(drawer.getByText('Leo: Passport')).toBeInTheDocument();
    expect(drawer.getByText(/Current: Dated 2 Aug 2025, expires 2 Aug 2030/)).toBeInTheDocument();
    expect(drawer.getByText(/Previous: Dated 1 Aug 2020, expires 1 Aug 2025/)).toBeInTheDocument();
    expect(drawer.getByRole('button', { name: /Download previous version/ })).toBeInTheDocument();
    expect(drawer.getByText('Added by you')).toBeInTheDocument();
    expect(drawer.getByText('New version uploaded by Sam')).toBeInTheDocument();
    expect(drawer.getByRole('button', { name: /Upload renewed version/ })).toBeInTheDocument();
    expect(drawer.getByRole('button', { name: /View full screen/ })).toBeInTheDocument();
    expect(drawer.queryByRole('button', { name: /Restore/ })).not.toBeInTheDocument();

    await user.click(drawer.getByRole('button', { name: /Archive/ }));
    expect(archive).toHaveBeenCalledWith({ familyId: 'fam', id: 'd2', version: 3, restore: false });
  });

  it('offers Restore on an archived document and says who archived it', async () => {
    const user = userEvent.setup();
    renderPage('/documents?doc=d13');

    const drawer = within(await screen.findByRole('dialog'));
    expect(drawer.getByText(/Archived by Sam on 5 Nov 2026. It's hidden from the library, not deleted./)).toBeInTheDocument();
    expect(drawer.getByRole('button', { name: /Upload newer version/ })).toBeInTheDocument();
    expect(drawer.queryByRole('button', { name: /^Archive$/ })).not.toBeInTheDocument();

    await user.click(drawer.getByRole('button', { name: /Restore/ }));
    expect(archive).toHaveBeenCalledWith({ familyId: 'fam', id: 'd13', version: 3, restore: true });
  });

  it('says plainly when a write was refused because the document changed (409)', async () => {
    const user = userEvent.setup();
    archive.mockRejectedValue({ response: { status: 409, data: { message: 'stale' } } });
    renderPage('/documents?doc=d1');

    const drawer = within(await screen.findByRole('dialog'));
    expect(drawer.getByText(/Many countries want 6 months left on arrival/)).toBeInTheDocument();
    await user.click(drawer.getByRole('button', { name: /Archive/ }));

    expect(await drawer.findByRole('alert')).toHaveTextContent(CONFLICT_MESSAGE);
  });

  it('edits details at the version the parent saw', async () => {
    const user = userEvent.setup();
    renderPage('/documents?doc=d5');

    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /Edit details/ }));
    const title = await screen.findByLabelText('Title');
    expect(title).toHaveValue('Asthma care plan');
    await user.clear(title);
    await user.type(title, 'Asthma plan (GP)');
    await user.click(screen.getByRole('button', { name: 'Save details' }));

    expect(update).toHaveBeenCalledWith({
      familyId: 'fam',
      id: 'd5',
      version: 3,
      details: expect.objectContaining({ title: 'Asthma plan (GP)', childIds: ['c2'], section: 'HEALTH', dueOn: '2027-01-10', dueKind: 'REVIEW' }),
    });
  });

  it('opens the full-screen viewer in place of the drawer', async () => {
    const user = userEvent.setup();
    renderPage('/documents?doc=d1');

    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /View full screen/ }));

    const viewer = await screen.findByRole('dialog', { name: 'Passport, full screen' });
    expect(await within(viewer).findByRole('img', { name: 'Passport' })).toHaveAttribute('src', 'blob:preview');
    await user.click(within(viewer).getByRole('button', { name: 'Close full screen' }));
    expect(screen.queryByRole('dialog', { name: 'Passport, full screen' })).not.toBeInTheDocument();
  });

  it('adds a document read by CoParent, highlighting what it filled in', async () => {
    const user = userEvent.setup();
    read.mockResolvedValue({
      childIds: ['c2'], section: 'IDENTITY', title: 'Passport', datedOn: '2025-08-02', dueOn: '2030-08-02', dueKind: 'EXPIRES', confidence: 0.9,
    });
    renderPage();

    await user.click(screen.getAllByRole('button', { name: /^Add document/ })[0]);
    const file = jpeg();
    await user.upload(await screen.findByLabelText('Choose document files'), file);

    expect(read).toHaveBeenCalledWith({ familyId: 'fam', files: [file] });
    const title = await screen.findByLabelText('Title');
    expect(title).toHaveValue('Passport');
    expect(title.closest('.document-field')).toHaveClass('document-field--ai');
    expect(screen.getByLabelText('Note (optional)').closest('.document-field')).not.toHaveClass('document-field--ai');
    expect(screen.getByText(/Here's what we read/)).toBeInTheDocument();
    expect(screen.getByText('Sam gets one email for everything you add in the next 15 minutes.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save document' }));
    expect(create).toHaveBeenCalledWith({
      familyId: 'fam',
      details: {
        childIds: ['c2'], section: 'IDENTITY', title: 'Passport', datedOn: '2025-08-02',
        dueOn: '2030-08-02', dueKind: 'EXPIRES', note: null,
      },
      files: [file],
    });
  });

  it('lets a parent fill it in themselves without the upload ever being read', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getAllByRole('button', { name: /^Add document/ })[0]);
    await user.click(await screen.findByRole('button', { name: 'Fill in myself instead' }));
    await user.click(screen.getByRole('checkbox', { name: 'Mia' }));
    await user.selectOptions(screen.getByLabelText('Section'), 'HEALTH');
    await user.type(screen.getByLabelText('Title'), 'Orthodontist referral letter');
    fireEvent.change(screen.getByLabelText('Dated'), { target: { value: '2026-10-02' } });
    const file = new File(['%PDF'], 'referral.pdf', { type: 'application/pdf' });
    await user.upload(screen.getByLabelText('Choose document files'), file);

    expect(screen.queryByText(/Here's what we read/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save document' }));

    expect(read).not.toHaveBeenCalled();
    expect(create).toHaveBeenCalledWith({
      familyId: 'fam',
      details: {
        childIds: ['c1'], section: 'HEALTH', title: 'Orthodontist referral letter', datedOn: '2026-10-02',
        dueOn: null, dueKind: null, note: null,
      },
      files: [file],
    });
  });

  it('falls back to filling in by hand when reading is unavailable', async () => {
    const user = userEvent.setup();
    read.mockRejectedValue({ response: { status: 503 } });
    renderPage();

    await user.click(screen.getAllByRole('button', { name: /^Add document/ })[0]);
    await user.upload(await screen.findByLabelText('Choose document files'), jpeg());

    expect(await screen.findByText(/Reading documents isn't available right now/)).toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toHaveValue('');
    await user.click(screen.getByRole('button', { name: 'Save document' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Give the document a title.');
    expect(create).not.toHaveBeenCalled();
  });

  it('keeps a letter from school in the chosen section, or dismisses it', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /^From school/ }));
    const card = within(await screen.findByRole('listitem', { name: 'Year 6 residential: consent form' }));
    expect(card.getByText('Sent to Year 6 parents. Mia is in Year 6.')).toBeInTheDocument();
    expect(card.getByText('Kingswood-consent.pdf')).toBeInTheDocument();
    expect(card.queryByText('Personal letter')).not.toBeInTheDocument();
    expect(within(screen.getByRole('listitem', { name: 'Instrumental lessons report: Mia' })).getByText('Personal letter')).toBeInTheDocument();

    await user.selectOptions(card.getByRole('combobox', { name: 'File under' }), 'HEALTH');
    await user.click(card.getByRole('button', { name: "Keep in Mia's Health" }));
    expect(keep).toHaveBeenCalledWith({
      familyId: 'fam', offerId: 's1', childIds: ['c1'], section: 'HEALTH', title: 'Year 6 residential: consent form',
    });

    await user.click(within(screen.getByRole('listitem', { name: 'Instrumental lessons report: Mia' })).getByRole('button', { name: 'Not needed' }));
    expect(dismiss).toHaveBeenCalledWith({ familyId: 'fam', offerId: 's2' });
  });

  it('links a report prompt to Insight Tracking only for the real origin, and prefills the add drawer', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /^From school/ }));
    const real = within(await screen.findByRole('listitem', { name: "Leo's report is ready on Insight Tracking" }));
    const link = real.getByRole('link', { name: /Open Insight Tracking/ });
    expect(link).toHaveAttribute('href', 'https://app.insighttracking.com/parent/report/123');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(link).toHaveAttribute('target', '_blank');
    const forged = within(screen.getByRole('listitem', { name: "Mia's report is ready on Insight Tracking" }));
    expect(forged.queryByRole('link')).not.toBeInTheDocument();
    expect(forged.getByRole('button', { name: /Add it here/ })).toBeInTheDocument();

    await user.click(real.getByRole('button', { name: /Add it here/ }));

    expect(await screen.findByText(/Filled in from the school's email/)).toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toHaveValue('Autumn report 2026/27');
    expect(screen.getByLabelText('Section')).toHaveValue('SCHOOL');
    expect(screen.getByLabelText('Dated')).toHaveValue('2026-11-24');
    expect(screen.getByRole('checkbox', { name: 'Leo' })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Mia' })).not.toBeChecked();
    expect(screen.getByText('Drop the report PDF you downloaded')).toBeInTheDocument();

    const pdf = new File(['%PDF'], 'report.pdf', { type: 'application/pdf' });
    await user.upload(screen.getByLabelText('Choose document files'), pdf);
    await user.click(screen.getByRole('button', { name: 'Save document' }));

    expect(read).not.toHaveBeenCalled();
    await waitFor(() => expect(create).toHaveBeenCalledWith({
      familyId: 'fam',
      details: {
        childIds: ['c2'], section: 'SCHOOL', title: 'Autumn report 2026/27', datedOn: '2026-11-24',
        dueOn: null, dueKind: null, note: null, schoolOfferId: 's4',
      },
      files: [pdf],
    }));
  });

  it('uploads a renewed version with new dates at the version the parent saw', async () => {
    const user = userEvent.setup();
    read.mockResolvedValue({ childIds: null, section: null, title: null, datedOn: '2027-02-01', dueOn: '2032-02-01', dueKind: 'EXPIRES', confidence: 0.8 });
    renderPage('/documents?doc=d1');

    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /Upload renewed version/ }));
    const file = jpeg('new-passport.jpg');
    await user.upload(await screen.findByLabelText('Choose document files'), file);
    expect(await screen.findByLabelText('Dated')).toHaveValue('2027-02-01');
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save new version' }));

    expect(addVersion).toHaveBeenCalledWith({
      familyId: 'fam', id: 'd1', version: 3,
      details: { datedOn: '2027-02-01', dueOn: '2032-02-01', dueKind: 'EXPIRES' },
      files: [file],
    });
  });
});
