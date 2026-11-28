import { Camera, CircleAlert, FileText, Info, Sparkles, Upload, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type DragEvent, type FormEvent } from 'react';

import {
  useAddDocumentVersion,
  useCreateDocument,
  useReadDocument,
  useUpdateDocument,
} from '../../hooks/api/useDocuments';
import type {
  DocumentDetails,
  DocumentProposal,
  DocumentSection,
  DueKind,
  FamilyDocument,
} from '../../types/documents';
import { useToast } from '../ui/ToastProvider';

import { documentErrorMessage, statusOf } from './documentErrors';
import { DocumentSheet } from './DocumentSheet';
import {
  ACCEPTED_TYPES,
  MAX_FILES,
  SECTIONS,
  filesProblem,
  isSection,
  type DocChild,
} from './documentText';

/** What the add drawer is for: a new document (maybe a school report), a new version, or an edit. */
export type AddDocumentMode =
  | {
      kind: 'create';
      /** Filled in from a school report prompt; the document fulfils that offer. */
      prefill?: {
        schoolOfferId: string;
        childIds: string[];
        section: DocumentSection;
        title: string;
        datedOn: string | null;
      };
    }
  | { kind: 'version'; document: FamilyDocument }
  | { kind: 'edit'; document: FamilyDocument };

interface FormValues {
  childIds: string[];
  section: DocumentSection;
  title: string;
  datedOn: string;
  dueOn: string;
  dueKind: DueKind;
  note: string;
}

type Field = keyof FormValues;

const EMPTY: FormValues = {
  childIds: [],
  section: 'IDENTITY',
  title: '',
  datedOn: '',
  dueOn: '',
  dueKind: 'EXPIRES',
  note: '',
};

function initialValues(mode: AddDocumentMode): FormValues {
  if (mode.kind === 'create') {
    const prefill = mode.prefill;
    return prefill
      ? { ...EMPTY, childIds: prefill.childIds, section: prefill.section, title: prefill.title, datedOn: prefill.datedOn ?? '' }
      : EMPTY;
  }
  const document = mode.document;
  const base: FormValues = {
    childIds: document.childIds,
    section: isSection(document.section) ? document.section : 'OTHER',
    title: document.title,
    datedOn: document.datedOn ?? '',
    dueOn: document.dueOn ?? '',
    dueKind: document.dueKind ?? 'EXPIRES',
    note: document.note ?? '',
  };
  // A new version brings new dates; the old ones stay on the version they belong to.
  return mode.kind === 'version' ? { ...base, datedOn: '', dueOn: '', dueKind: document.dueKind ?? 'EXPIRES' } : base;
}

/** The proposal's non-empty values, applied over the form, and which fields they filled. */
function applyProposal(
  values: FormValues,
  proposal: DocumentProposal,
  children: DocChild[],
  datesOnly: boolean,
): { values: FormValues; filled: Set<Field> } {
  const next = { ...values };
  const filled = new Set<Field>();
  if (!datesOnly) {
    if (Array.isArray(proposal.childIds)) {
      next.childIds = proposal.childIds.filter((id) => children.some((child) => child.id === id));
      filled.add('childIds');
    }
    if (isSection(proposal.section)) {
      next.section = proposal.section;
      filled.add('section');
    }
    if (proposal.title?.trim()) {
      next.title = proposal.title.trim().slice(0, 120);
      filled.add('title');
    }
  }
  if (proposal.datedOn) {
    next.datedOn = proposal.datedOn;
    filled.add('datedOn');
  }
  if (proposal.dueOn) {
    next.dueOn = proposal.dueOn;
    next.dueKind = proposal.dueKind ?? 'EXPIRES';
    filled.add('dueOn');
  }
  return { values: next, filled };
}

function FileStrip({ files, onRemove }: { files: File[]; onRemove: (index: number) => void }) {
  const urls = useMemo(
    () => files.map((file) => (file.type.startsWith('image/') ? URL.createObjectURL(file) : null)),
    [files],
  );
  useEffect(() => () => urls.forEach((url) => url && URL.revokeObjectURL(url)), [urls]);
  return (
    <ul className="document-files" aria-label="Files to upload">
      {files.map((file, index) => (
        <li key={`${file.name}-${index}`} className="document-files__item">
          <span className={`document-files__thumb${urls[index] ? '' : ' document-files__thumb--pdf'}`}>
            {urls[index] ? <img src={urls[index] ?? ''} alt="" /> : <FileText size={18} aria-hidden="true" />}
          </span>
          <span className="document-files__name">{file.name}</span>
          <button type="button" className="document-files__remove" onClick={() => onRemove(index)} aria-label={`Remove ${file.name}`}>
            <X size={12} aria-hidden="true" />
          </button>
        </li>
      ))}
    </ul>
  );
}

export function AddDocumentDrawer({
  open,
  familyId,
  mode,
  children,
  otherActiveName,
  onClose,
  onSaved,
}: {
  open: boolean;
  familyId: string;
  mode: AddDocumentMode;
  children: DocChild[];
  /** The other parent's first name when they are active: only then do they get the batch email. */
  otherActiveName: string | null;
  onClose: () => void;
  onSaved?: (document: FamilyDocument) => void;
}) {
  const { showToast } = useToast();
  const read = useReadDocument();
  const create = useCreateDocument();
  const addVersion = useAddDocumentVersion();
  const update = useUpdateDocument();
  const prefilled = mode.kind === 'create' && !!mode.prefill;
  const [step, setStep] = useState<'pick' | 'reading' | 'review'>(
    mode.kind === 'edit' || prefilled ? 'review' : 'pick',
  );
  // "Fill in myself" keeps the files away from the reading model altogether.
  const [manual, setManual] = useState(prefilled);
  const [files, setFiles] = useState<File[]>([]);
  const [values, setValues] = useState<FormValues>(() => initialValues(mode));
  const [filled, setFilled] = useState<Set<Field>>(
    () => new Set<Field>(prefilled ? ['childIds', 'section', 'title', 'datedOn'] : []),
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const photoInput = useRef<HTMLInputElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const reading = useRef(0);

  const datesOnly = mode.kind === 'version';
  const takesFiles = mode.kind !== 'edit';
  const saving = create.isPending || addVersion.isPending || update.isPending;

  useEffect(() => () => {
    // Closing while a read is in flight discards its answer.
    reading.current += 1;
  }, []);

  const set = <K extends Field>(key: K, value: FormValues[K]) => {
    setValues((current) => ({ ...current, [key]: value }));
    setFilled((current) => {
      if (!current.has(key)) return current;
      const next = new Set(current);
      next.delete(key);
      return next;
    });
  };

  const readFiles = async (chosen: File[]) => {
    const attempt = reading.current + 1;
    reading.current = attempt;
    setStep('reading');
    setNotice(null);
    try {
      const proposal = await read.mutateAsync({ familyId, files: chosen });
      if (reading.current !== attempt) return;
      const applied = applyProposal(values, proposal, children, datesOnly);
      setValues(applied.values);
      setFilled(applied.filled);
      if (applied.filled.size === 0) setNotice("We couldn't make out the details, so fill them in yourself.");
    } catch (failure) {
      if (reading.current !== attempt) return;
      setManual(true);
      setNotice(
        statusOf(failure) === 503
          ? "Reading documents isn't available right now, so fill in the details yourself."
          : "We couldn't read that document, so fill in the details yourself.",
      );
    }
    setStep('review');
  };

  const addFiles = (list: FileList | File[] | null) => {
    const incoming = Array.from(list ?? []);
    if (!incoming.length) return;
    const next = [...files, ...incoming].slice(0, MAX_FILES);
    const problem = filesProblem(next);
    setError(problem);
    setFiles(next);
    if (step === 'pick') {
      if (manual || problem) setStep('review');
      else void readFiles(next);
    }
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    addFiles(event.dataTransfer?.files ?? null);
  };

  const pickers = (compact = false) => (
    <div
      className={`document-drop${compact ? ' document-drop--compact' : ''}${dragging ? ' is-dragging' : ''}`}
      onDragOver={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
    >
      <Upload size={compact ? 20 : 28} aria-hidden="true" />
      <strong>
        {prefilled ? 'Drop the report PDF you downloaded' : compact ? 'Add the photos or PDF' : 'Drop a PDF or photos here'}
      </strong>
      {!compact && <p>Several photos of one document are kept together as one document.</p>}
      <div className="document-drop__buttons">
        <button type="button" className={`cp-button ${compact ? 'cp-button--secondary cp-button--sm' : 'cp-button--primary'}`} onClick={() => photoInput.current?.click()}>
          <Camera size={16} aria-hidden="true" /> Take photos
        </button>
        <button type="button" className={`cp-button cp-button--secondary${compact ? ' cp-button--sm' : ''}`} onClick={() => fileInput.current?.click()}>
          <Upload size={16} aria-hidden="true" /> Choose file
        </button>
      </div>
      <input
        ref={photoInput}
        className="document-file-input"
        type="file"
        accept="image/*"
        capture="environment"
        multiple
        tabIndex={-1}
        aria-label="Take photos of the document"
        onChange={(event) => {
          addFiles(event.target.files);
          event.target.value = '';
        }}
      />
      <input
        ref={fileInput}
        className="document-file-input"
        type="file"
        accept={ACCEPTED_TYPES.join(',')}
        multiple
        tabIndex={-1}
        aria-label="Choose document files"
        onChange={(event) => {
          addFiles(event.target.files);
          event.target.value = '';
        }}
      />
      {!compact && (
        <p className="document-drop__manual">
          CoParent reads the document to fill in the details.{' '}
          <button
            type="button"
            className="document-link"
            onClick={() => {
              setManual(true);
              setStep('review');
            }}
          >
            Fill in myself instead
          </button>
        </p>
      )}
    </div>
  );

  const validate = (): string | null => {
    if (takesFiles) {
      const problem = filesProblem(files);
      if (problem) return problem;
    }
    if (!datesOnly && !values.title.trim()) return 'Give the document a title.';
    if (!datesOnly && values.title.trim().length > 120) return 'Keep the title to 120 characters.';
    if (!values.datedOn) return 'Add the date on the document.';
    if (values.dueOn && values.datedOn && values.dueOn < values.datedOn) {
      return 'The expiry or review date is before the date on the document.';
    }
    if (values.note.length > 1000) return 'Keep the note to 1,000 characters.';
    return null;
  };

  const details = (): DocumentDetails => ({
    childIds: values.childIds,
    section: values.section,
    title: values.title.trim(),
    datedOn: values.datedOn || null,
    dueOn: values.dueOn || null,
    dueKind: values.dueOn ? values.dueKind : null,
    note: values.note.trim() || null,
    ...(mode.kind === 'create' && mode.prefill ? { schoolOfferId: mode.prefill.schoolOfferId } : {}),
  });

  const save = async (event: FormEvent) => {
    event.preventDefault();
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    const emailed = otherActiveName ? `${otherActiveName} will get an email.` : undefined;
    try {
      let saved: FamilyDocument;
      if (mode.kind === 'create') {
        saved = await create.mutateAsync({ familyId, details: details(), files });
        showToast({ variant: 'success', title: 'Saved', description: emailed ?? `${saved.title} is in the library.` });
      } else if (mode.kind === 'version') {
        saved = await addVersion.mutateAsync({
          familyId,
          id: mode.document.id,
          version: mode.document.version,
          details: { datedOn: values.datedOn || null, dueOn: values.dueOn || null, dueKind: values.dueOn ? values.dueKind : null },
          files,
        });
        showToast({ variant: 'success', title: 'New version saved', description: emailed ?? 'The earlier one stays in its history.' });
      } else {
        saved = await update.mutateAsync({ familyId, id: mode.document.id, version: mode.document.version, details: details() });
        showToast({ variant: 'success', title: 'Details saved', description: saved.title });
      }
      onSaved?.(saved);
      onClose();
    } catch (failure) {
      setError(documentErrorMessage(failure, 'That did not save. Try again.'));
    }
  };

  const fieldClass = (key: Field) => `document-field${filled.has(key) ? ' document-field--ai' : ''}`;
  const toggleChild = (id: string) =>
    set('childIds', values.childIds.includes(id)
      ? values.childIds.filter((current) => current !== id)
      : children.filter((child) => child.id === id || values.childIds.includes(child.id)).map((child) => child.id));

  const title = mode.kind === 'edit'
    ? 'Edit details'
    : mode.kind === 'version'
      ? `Upload ${mode.document.dueKind === 'EXPIRES' && mode.document.dueOn ? 'renewed' : 'newer'} version`
      : 'Add a document';
  const subtitle = step === 'review'
    ? mode.kind === 'edit' ? mode.document.title : 'Check the details, then save'
    : mode.kind === 'version' ? mode.document.title : 'Photograph it or upload a file';

  return (
    <DocumentSheet
      open={open}
      title={title}
      description={mode.kind === 'edit' ? 'Change a document’s details' : 'Upload a document to the family library'}
      subtitle={subtitle}
      onClose={onClose}
      footer={step === 'review' ? (
        <>
          <button type="submit" form="document-form" className="cp-button cp-button--primary" disabled={saving}>
            {saving ? 'Saving…' : mode.kind === 'edit' ? 'Save details' : mode.kind === 'version' ? 'Save new version' : 'Save document'}
          </button>
          <button type="button" className="cp-button cp-button--quiet" onClick={onClose}>
            Cancel
          </button>
        </>
      ) : undefined}
    >
      {step === 'pick' && (
        <>
          {pickers()}
          <p className="document-callout document-callout--slate">
            <Info size={16} aria-hidden="true" />
            <span>
              JPEG, PNG, WebP or PDF, up to 20 MB each. Stored privately
              {otherActiveName ? ` and shared with ${otherActiveName}: everything here is visible to you both.` : '; everything here is visible to both parents.'}
            </span>
          </p>
          {error && <p className="document-callout document-callout--rose" role="alert"><CircleAlert size={16} aria-hidden="true" /><span>{error}</span></p>}
        </>
      )}

      {step === 'reading' && (
        <>
          {files.length > 0 && <FileStrip files={files} onRemove={() => undefined} />}
          <p className="document-reading" role="status">
            <span className="document-spin" aria-hidden="true" /> Reading the document…
          </p>
          <button
            type="button"
            className="document-link"
            onClick={() => {
              reading.current += 1;
              setManual(true);
              setStep('review');
            }}
          >
            Stop and fill in myself
          </button>
        </>
      )}

      {step === 'review' && (
        <form id="document-form" className="document-form" onSubmit={save} noValidate>
          {takesFiles && (files.length > 0 ? (
            <div className="document-form__files">
              <FileStrip files={files} onRemove={(index) => setFiles(files.filter((_, at) => at !== index))} />
              {files.length < MAX_FILES && pickers(true)}
            </div>
          ) : pickers(true))}

          {notice && (
            <p className="document-callout document-callout--slate" role="status">
              <Info size={16} aria-hidden="true" />
              <span>{notice}</span>
            </p>
          )}
          {filled.size > 0 && (
            <p className="document-callout document-callout--teal">
              <Sparkles size={16} aria-hidden="true" />
              <span>
                {prefilled ? "Filled in from the school's email." : "Here's what we read."} Highlighted fields were
                filled in for you, so check them before saving.
              </span>
            </p>
          )}

          {!datesOnly && (
            <>
              <div className="document-row2">
                <fieldset className={fieldClass('childIds')}>
                  <legend>For</legend>
                  <div className="document-choices">
                    {children.map((child) => (
                      <label key={child.id} className={`document-choice${values.childIds.includes(child.id) ? ' is-on' : ''}`}>
                        <input type="checkbox" checked={values.childIds.includes(child.id)} onChange={() => toggleChild(child.id)} />
                        {child.firstName}
                      </label>
                    ))}
                    <label className={`document-choice${values.childIds.length === 0 ? ' is-on' : ''}`}>
                      <input type="checkbox" checked={values.childIds.length === 0} onChange={() => set('childIds', [])} />
                      Whole family
                    </label>
                  </div>
                </fieldset>
                <div className={fieldClass('section')}>
                  <label htmlFor="document-section">Section</label>
                  <select id="document-section" value={values.section} onChange={(event) => set('section', event.target.value as DocumentSection)}>
                    {SECTIONS.map((section) => (
                      <option key={section.key} value={section.key}>{section.label}</option>
                    ))}
                  </select>
                </div>
              </div>
              <div className={fieldClass('title')}>
                <label htmlFor="document-title">Title</label>
                <input id="document-title" value={values.title} maxLength={120} placeholder="e.g. Passport" onChange={(event) => set('title', event.target.value)} />
              </div>
            </>
          )}

          <div className="document-row2">
            <div className={fieldClass('datedOn')}>
              <label htmlFor="document-dated">Dated</label>
              <input id="document-dated" type="date" value={values.datedOn} onChange={(event) => set('datedOn', event.target.value)} />
            </div>
            <div className={fieldClass('dueOn')}>
              <label htmlFor="document-due">Expires or review due (optional)</label>
              <input id="document-due" type="date" value={values.dueOn} onChange={(event) => set('dueOn', event.target.value)} />
              {values.dueOn && (
                <div className="document-seg" role="radiogroup" aria-label="Kind of date">
                  {(['EXPIRES', 'REVIEW'] as DueKind[]).map((kind) => (
                    <button
                      key={kind}
                      type="button"
                      role="radio"
                      aria-checked={values.dueKind === kind}
                      className={values.dueKind === kind ? 'is-on' : ''}
                      onClick={() => set('dueKind', kind)}
                    >
                      {kind === 'EXPIRES' ? 'Expires' : 'Review due'}
                    </button>
                  ))}
                </div>
              )}
              <p className="document-hint">
                {values.dueOn && values.dueKind === 'REVIEW'
                  ? "We'll remind you both a month and a week before."
                  : "We'll remind you both 6 and 3 months before."}
              </p>
            </div>
          </div>

          {takesFiles && otherActiveName && (
            <p className="document-hint">{otherActiveName} gets one email for everything you add in the next 15 minutes.</p>
          )}

          {!datesOnly && (
            <div className="document-field">
              <label htmlFor="document-note">Note (optional)</label>
              <textarea id="document-note" rows={2} maxLength={1000} value={values.note} placeholder="e.g. renewal photos are in the drawer" onChange={(event) => set('note', event.target.value)} />
            </div>
          )}

          {error && (
            <p className="document-callout document-callout--rose" role="alert">
              <CircleAlert size={16} aria-hidden="true" />
              <span>{error}</span>
            </p>
          )}
        </form>
      )}
    </DocumentSheet>
  );
}
