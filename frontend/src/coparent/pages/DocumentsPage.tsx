import {
  Camera,
  Clock,
  FileText,
  GraduationCap,
  IdCard,
  Info,
  Mail,
  Plus,
  Scale,
  Search,
  Star,
  Stethoscope,
  Upload,
} from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';

import { briefingChildren } from '../components/dashboard/briefing';
import { AddDocumentDrawer, type AddDocumentMode } from '../components/documents/AddDocumentDrawer';
import { ChildDots } from '../components/documents/ChildDot';
import { DocumentCard } from '../components/documents/DocumentCard';
import { DocumentDetailDrawer } from '../components/documents/DocumentDetailDrawer';
import {
  SECTIONS,
  attentionItems,
  currentVersion,
  groupBySection,
  matchesChild,
  matchesSearch,
  ymdToday,
  type ChildFilter,
  type DocChild,
} from '../components/documents/documentText';
import { DocumentViewer } from '../components/documents/DocumentViewer';
import { SchoolOffersDrawer } from '../components/documents/SchoolOffersDrawer';
import { downloadDocumentFile } from '../components/documents/useDocumentFileUrl';
import { firstName } from '../components/expenses/parentTone';
import { useToast } from '../components/ui/ToastProvider';
import { useChildren, useCurrentParentId, useFamilies, useParentsWithInvited } from '../hooks/api';
import {
  useDismissSchoolOffer,
  useDocument,
  useDocuments,
  useKeepSchoolOffer,
  useSchoolOffers,
} from '../hooks/api/useDocuments';
import type { DocumentSection, DocumentVersion, FamilyDocument, SchoolOffer } from '../types/documents';

const SECTION_ICONS: Record<DocumentSection, ReactNode> = {
  IDENTITY: <IdCard size={16} aria-hidden="true" />,
  HEALTH: <Stethoscope size={16} aria-hidden="true" />,
  SCHOOL: <GraduationCap size={16} aria-hidden="true" />,
  AGREEMENTS: <Scale size={16} aria-hidden="true" />,
  OTHER: <Star size={16} aria-hidden="true" />,
};

const INTRO = "The children's important papers, kept in one place you can both reach.";

const DocumentsPage = () => {
  const { showToast } = useToast();
  const [params, setParams] = useSearchParams();
  const { data: families = [], isLoading: familiesLoading } = useFamilies();
  const familyId = families[0]?.id;
  const meId = useCurrentParentId(familyId);
  // With the invited co-parent: documents can be added before they join, and history names them.
  const { data: parents = [] } = useParentsWithInvited(familyId);
  const { data: childRows = [] } = useChildren(familyId);
  const current = useDocuments(familyId);
  const archived = useDocuments(familyId, true);
  const offersQuery = useSchoolOffers(familyId);
  const docId = params.get('doc');
  const detail = useDocument(familyId, docId);
  const keep = useKeepSchoolOffer();
  const dismiss = useDismissSchoolOffer();

  const [childFilter, setChildFilter] = useState<ChildFilter>('all');
  const [showArchived, setShowArchived] = useState(false);
  const [query, setQuery] = useState('');
  const [addMode, setAddMode] = useState<AddDocumentMode | null>(null);
  const [offersOpen, setOffersOpen] = useState(false);
  const [viewing, setViewing] = useState(false);

  const today = ymdToday(new Date());
  const children: DocChild[] = useMemo(() => briefingChildren(childRows), [childRows]);
  const documents = useMemo(() => current.data ?? [], [current.data]);
  const archivedDocuments = useMemo(() => archived.data ?? [], [archived.data]);
  const offers = useMemo(() => offersQuery.data ?? [], [offersQuery.data]);

  const otherActive = parents.find((parent) => parent.id !== meId && parent.status === 'active');
  const otherActiveName = otherActive ? firstName(otherActive) : null;
  const nameOf = (parentId: string | null | undefined) =>
    parentId && parentId === meId ? 'you' : firstName(parents.find((parent) => parent.id === parentId), 'your co-parent');

  const open = docId
    ? detail.data ?? documents.find((entry) => entry.id === docId) ?? archivedDocuments.find((entry) => entry.id === docId) ?? null
    : null;

  const setDoc = (id: string | null) =>
    setParams(
      (existing) => {
        const next = new URLSearchParams(existing);
        if (id) next.set('doc', id);
        else next.delete('doc');
        return next;
      },
      { replace: true },
    );

  const download = async (document: FamilyDocument, version: DocumentVersion) => {
    if (!familyId) return;
    try {
      for (const [index, file] of version.files.entries()) {
        await downloadDocumentFile(familyId, document.id, file, document.title, version.files.length > 1 ? index + 1 : undefined);
      }
    } catch {
      showToast({ variant: 'error', title: 'Download failed', description: 'Try again.' });
    }
  };

  const keepOffer = async (offer: SchoolOffer, section: DocumentSection) => {
    if (!familyId) return;
    await keep.mutateAsync({ familyId, offerId: offer.id, childIds: offer.childIds, section, title: offer.title });
    showToast({
      variant: 'success',
      title: 'Kept',
      description: otherActiveName ? `Kept and shared with ${otherActiveName}.` : `${offer.title} is in the library.`,
    });
  };

  const dismissOffer = async (offer: SchoolOffer) => {
    if (!familyId) return;
    await dismiss.mutateAsync({ familyId, offerId: offer.id });
  };

  const addReport = (offer: SchoolOffer) => {
    setOffersOpen(false);
    setAddMode({
      kind: 'create',
      prefill: {
        schoolOfferId: offer.id,
        childIds: offer.childIds,
        section: 'SCHOOL',
        title: offer.suggestedTitle ?? offer.title,
        datedOn: offer.suggestedDatedOn,
      },
    });
  };

  if (familiesLoading || current.isLoading) {
    return <div className="document-page document-muted">Loading documents…</div>;
  }
  if (!familyId) {
    return (
      <div className="document-page">
        <h1 className="document-page__title">Documents</h1>
        <p className="document-muted">Set up your family to start keeping documents.</p>
      </div>
    );
  }

  const firstVisit = !current.isError && documents.length === 0 && archivedDocuments.length === 0 && !archived.isLoading;
  const pool = showArchived ? archivedDocuments : documents;
  const shown = pool
    .filter((document) => matchesChild(document, childFilter))
    .filter((document) => matchesSearch(document, query, children));
  const groups = groupBySection(shown);
  const attention = showArchived ? [] : attentionItems(documents, offers, children, today);
  const chips: { key: ChildFilter; label: string; ids?: string[] }[] = [
    { key: 'all', label: 'Everyone' },
    ...children.map((child) => ({ key: child.id, label: child.firstName, ids: [child.id] })),
    { key: 'family', label: 'Family', ids: [] },
  ];
  const countFor = (key: ChildFilter) => documents.filter((document) => matchesChild(document, key)).length;

  const fromSchoolButton = (
    <button type="button" className="cp-button cp-button--secondary" onClick={() => setOffersOpen(true)}>
      <Mail size={16} aria-hidden="true" /> From school
      {offers.length > 0 && <span className="document-count" aria-label={`${offers.length} waiting`}>{offers.length}</span>}
    </button>
  );

  return (
    <div className="document-page">
      <header className="document-page__head">
        <div>
          <h1 className="document-page__title">Documents</h1>
          <p className="document-page__sub">{INTRO}</p>
        </div>
        {(!firstVisit || offers.length > 0) && (
          <div className="document-page__actions">
            {fromSchoolButton}
            {!firstVisit && (
              <button type="button" className="cp-button cp-button--primary document-page__add" onClick={() => setAddMode({ kind: 'create' })}>
                <Plus size={16} aria-hidden="true" /> Add document
              </button>
            )}
          </div>
        )}
      </header>

      {current.isError && (
        <p className="document-callout document-callout--rose" role="alert">
          The documents could not be loaded. Reload the page to try again.
        </p>
      )}

      {firstVisit ? (
        <section className="document-empty">
          <FileText size={34} aria-hidden="true" />
          <h2>Start with the papers you reach for most</h2>
          <p>
            Photograph or upload a document. CoParent reads it and suggests which child it's for, what it is
            and when it expires. You check the details before anything is saved.
          </p>
          <div className="document-drop__buttons">
            <button type="button" className="cp-button cp-button--primary" onClick={() => setAddMode({ kind: 'create' })}>
              <Camera size={16} aria-hidden="true" /> Photograph a document
            </button>
            <button type="button" className="cp-button cp-button--secondary" onClick={() => setAddMode({ kind: 'create' })}>
              <Upload size={16} aria-hidden="true" /> Upload a file
            </button>
          </div>
          <ul className="document-starter">
            {SECTIONS.map((section) => (
              <li key={section.key}>
                <strong>{section.label}</strong>
                {section.hint}
              </li>
            ))}
          </ul>
        </section>
      ) : (
        <>
          {attention.length > 0 && (
            <ul className="document-attention" aria-label="Needs attention">
              {attention.map((item) => (
                <li key={item.key}>
                  <button
                    type="button"
                    className={`document-attention__item${item.kind === 'school' ? ' document-attention__item--school' : ''}`}
                    onClick={() => (item.kind === 'school' ? setOffersOpen(true) : item.documentId && setDoc(item.documentId))}
                  >
                    <span className="document-attention__icon" aria-hidden="true">
                      {item.kind === 'school' ? <Mail size={17} /> : <Clock size={17} />}
                    </span>
                    <span>
                      <strong>{item.title}</strong>
                      <span>{item.detail}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="document-bar">
            <label className="document-search">
              <Search size={16} aria-hidden="true" />
              <input
                type="search"
                aria-label="Search documents"
                placeholder="Search documents"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
              />
            </label>
            <div className="document-chips" role="group" aria-label="Show documents for">
              {chips.map((chip) => (
                <button
                  key={chip.key}
                  type="button"
                  className={`document-chip${childFilter === chip.key ? ' is-on' : ''}`}
                  aria-pressed={childFilter === chip.key}
                  onClick={() => setChildFilter(chip.key)}
                >
                  {chip.ids && <ChildDots ids={chip.ids} children={children} />}
                  {chip.label} <span className="document-chip__count">{countFor(chip.key)}</span>
                </button>
              ))}
              <button
                type="button"
                className={`document-chip${showArchived ? ' is-on' : ''}`}
                aria-pressed={showArchived}
                onClick={() => setShowArchived(!showArchived)}
              >
                Archived <span className="document-chip__count">{archivedDocuments.length}</span>
              </button>
            </div>
          </div>

          {showArchived && (
            <p className="document-callout document-callout--slate">
              <Info size={16} aria-hidden="true" />
              <span>Archived documents are hidden from the library but never deleted. Either of you can restore one.</span>
            </p>
          )}

          {groups.map(({ section, documents: inSection }) => (
            <section key={section.key} className="document-section" aria-labelledby={`document-section-${section.key}`}>
              <h2 id={`document-section-${section.key}`} className="document-section__title">
                {SECTION_ICONS[section.key]} {section.label} <small>{inSection.length}</small>
              </h2>
              <div className="document-grid">
                {inSection.map((document) => (
                  <DocumentCard key={document.id} document={document} children={children} today={today} onOpen={() => setDoc(document.id)} />
                ))}
              </div>
            </section>
          ))}
          {shown.length === 0 && (
            <p className="document-empty-note">
              {showArchived && pool.length === 0 ? 'Nothing is archived.' : 'Nothing matches.'}
            </p>
          )}

          <button type="button" className="document-fab" aria-label="Add document" onClick={() => setAddMode({ kind: 'create' })}>
            <Plus size={24} aria-hidden="true" />
          </button>
        </>
      )}

      {open && (
        <DocumentDetailDrawer
          open={!viewing && !addMode}
          familyId={familyId}
          document={open}
          children={children}
          nameOf={nameOf}
          today={today}
          otherActiveName={otherActiveName}
          onClose={() => setDoc(null)}
          onView={() => setViewing(true)}
          onDownload={(version) => void download(open, version)}
          onNewVersion={() => setAddMode({ kind: 'version', document: open })}
          onEdit={() => setAddMode({ kind: 'edit', document: open })}
        />
      )}

      {open && viewing && currentVersion(open) && (
        <DocumentViewer
          familyId={familyId}
          documentId={open.id}
          title={open.title}
          version={currentVersion(open)}
          onDownload={() => void download(open, currentVersion(open))}
          onClose={() => setViewing(false)}
        />
      )}

      {addMode && (
        <AddDocumentDrawer
          open
          familyId={familyId}
          mode={addMode}
          children={children}
          otherActiveName={otherActiveName}
          onClose={() => setAddMode(null)}
        />
      )}

      <SchoolOffersDrawer
        open={offersOpen}
        offers={offers}
        children={children}
        onClose={() => setOffersOpen(false)}
        onKeep={keepOffer}
        onDismiss={dismissOffer}
        onAddReport={addReport}
      />
    </div>
  );
};

export default DocumentsPage;
