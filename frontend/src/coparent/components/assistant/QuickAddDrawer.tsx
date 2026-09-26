import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  ImagePlus,
  LoaderCircle,
  Trash2,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Drawer } from 'vaul';

import {
  useAnalyseAssistantInput,
  useAssistantBatch,
  useAssistantBatches,
  useAssistantEventCategories,
  useChildren,
  useConversations,
  useCurrentUser,
  useEvents,
  useFamilies,
  useParents,
  useScheduleChangeRequests,
} from '../../hooks/api';
import { useOnlineStatus } from '../../lib/pwa/useOnlineStatus';

import { AssistantActionCard, type AssistantEditorOptions } from './AssistantActionCard';

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const MAX_IMAGE_SIZE = 10 * 1024 * 1024;
type AssistantStep = 'capture' | 'review';

export function QuickAddDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const families = useFamilies();
  const [familyId, setFamilyId] = useState('');
  const [text, setText] = useState('');
  const [image, setImage] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [inputError, setInputError] = useState<string | null>(null);
  const [batchId, setBatchId] = useState<string>();
  const [step, setStep] = useState<AssistantStep>('capture');
  const online = useOnlineStatus();
  const analyse = useAnalyseAssistantInput();
  const recent = useAssistantBatches(familyId || undefined);
  const batch = useAssistantBatch(familyId || undefined, batchId);
  const events = useEvents(familyId || undefined);
  const categories = useAssistantEventCategories(familyId || undefined);
  const requests = useScheduleChangeRequests(familyId || undefined);
  const conversations = useConversations(familyId || undefined);
  const children = useChildren(familyId || undefined);
  const parents = useParents(familyId || undefined);
  const currentUser = useCurrentUser();

  useEffect(() => {
    if (!familyId && families.data?.length) setFamilyId(families.data[0].id);
  }, [families.data, familyId]);

  const selectedFamily = useMemo(
    () => families.data?.find((family) => family.id === familyId),
    [families.data, familyId],
  );

  const currentParentId = currentUser.data?.profiles
    .find((profile) => profile.familyId === familyId)?.id;
  const editorOptions = useMemo<AssistantEditorOptions>(() => ({
    eventId: events.data?.map((event) => ({ value: event.id, label: event.title })) ?? [],
    originalEventId: events.data?.map((event) => ({ value: event.id, label: event.title })) ?? [],
    categoryId: categories.data?.filter((category) => !category.isSystem)
      .map((category) => ({ value: category.id, label: category.name })) ?? [],
    requestId: requests.data?.filter((request) => request.status === 'pending'
      && request.requestedBy === currentParentId)
      .map((request) => ({ value: request.id, label: request.reason })) ?? [],
    conversationId: conversations.data?.map((conversation) => ({
      value: conversation.id,
      label: conversation.subject,
    })) ?? [],
    childId: children.data?.map((child) => ({ value: child.id, label: child.fullName })) ?? [],
    childIds: children.data?.map((child) => ({ value: child.id, label: child.fullName })) ?? [],
    parentId: parents.data?.map((parent) => ({ value: parent.id, label: parent.fullName })) ?? [],
    parentIds: parents.data?.map((parent) => ({ value: parent.id, label: parent.fullName })) ?? [],
    recipientId: parents.data?.filter((parent) => parent.id !== currentParentId)
      .map((parent) => ({ value: parent.id, label: parent.fullName })) ?? [],
  }), [categories.data, children.data, conversations.data, currentParentId, events.data,
    parents.data, requests.data]);

  const clearImage = () => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setPreviewUrl(null);
    setImage(null);
  };

  const dismiss = () => {
    clearImage();
    setText('');
    setInputError(null);
    setBatchId(undefined);
    setStep('capture');
    analyse.reset();
    onClose();
  };

  useEffect(() => () => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const chooseImage = (file?: File) => {
    setInputError(null);
    if (!file) return;
    if (!IMAGE_TYPES.includes(file.type)) {
      setInputError('Choose a JPEG, PNG, or WebP image.');
      return;
    }
    if (file.size > MAX_IMAGE_SIZE) {
      setInputError('The image must be 10 MB or smaller.');
      return;
    }
    clearImage();
    setImage(file);
    setPreviewUrl(URL.createObjectURL(file));
  };

  const submit = async () => {
    setInputError(null);
    if (!familyId || (!text.trim() && !image)) {
      setInputError('Add some text or choose an image first.');
      return;
    }
    if (text.length > 20_000) {
      setInputError('Text must be 20,000 characters or fewer.');
      return;
    }
    try {
      const created = await analyse.mutateAsync({ familyId, text, image });
      setBatchId(created.id);
      setText('');
      clearImage();
      setStep('review');
    } catch {
      // Source input intentionally remains in browser memory for a retry.
    }
  };

  const showReview = () => {
    const reviewBatchId = batchId ?? recent.data?.[0]?.id;
    if (!reviewBatchId) return;
    setBatchId(reviewBatchId);
    setStep('review');
  };

  return (
    <Drawer.Root open={open} onOpenChange={(nextOpen) => !nextOpen && dismiss()} direction="right">
      <Drawer.Portal>
        <Drawer.Overlay className="assistant-drawer__overlay" />
        <Drawer.Content className="assistant-drawer">
          <header className="assistant-drawer__header">
            <div>
              <p className="assistant-drawer__eyebrow">Private proposal workspace</p>
              <Drawer.Title>Quick add</Drawer.Title>
              <Drawer.Description>
                Turn a note or image into actions you review one by one.
              </Drawer.Description>
            </div>
            <button type="button" className="assistant-drawer__close" onClick={dismiss} aria-label="Close Quick add">
              <X size={20} />
            </button>
          </header>

          <nav className="assistant-steps" aria-label="Quick add steps">
            <button
              type="button"
              className={step === 'capture' ? 'assistant-step assistant-step--active' : 'assistant-step assistant-step--complete'}
              aria-current={step === 'capture' ? 'step' : undefined}
              onClick={() => setStep('capture')}
            >
              <span className="assistant-step__number">1</span>
              <span>
                <strong>Describe it</strong>
                <small>Add a note or image</small>
              </span>
            </button>
            <span className="assistant-steps__line" aria-hidden="true" />
            <button
              type="button"
              className={step === 'review' ? 'assistant-step assistant-step--active' : 'assistant-step'}
              aria-current={step === 'review' ? 'step' : undefined}
              disabled={!batchId && !recent.data?.length}
              onClick={showReview}
            >
              <span className="assistant-step__number">2</span>
              <span>
                <strong>Review actions</strong>
                <small>Approve one by one</small>
              </span>
            </button>
          </nav>

          <div className="assistant-drawer__body">
            {step === 'capture' && (
            <section className="assistant-capture" aria-labelledby="assistant-capture-title">
              <div className="assistant-stage__heading">
                <p>Step 1 of 2</p>
                <h2 id="assistant-capture-title">What should we pick out?</h2>
                <span>Paste a message, describe a change, or add a photo. Nothing changes until you approve an action.</span>
              </div>

              {families.data && families.data.length > 1 && (
                <label className="assistant-field">
                  <span>Family</span>
                  <select value={familyId} onChange={(event) => {
                    setFamilyId(event.target.value);
                    setBatchId(undefined);
                    setStep('capture');
                  }}>
                    {families.data.map((family) => (
                      <option key={family.id} value={family.id}>{family.name}</option>
                    ))}
                  </select>
                </label>
              )}

              <label className="assistant-field">
                <span>Note</span>
                <textarea
                  className="assistant-field__textarea"
                  rows={7}
                  maxLength={20_000}
                  value={text}
                  onChange={(event) => setText(event.target.value)}
                  placeholder="For example: School photo day is next Thursday at 9am. Remind Alex to bring the form."
                />
                <small>{text.length.toLocaleString()} / 20,000</small>
              </label>

              <div className="assistant-upload">
                {previewUrl ? (
                  <div className="assistant-upload__preview">
                    <img src={previewUrl} alt="Selected upload preview" />
                    <button type="button" onClick={clearImage}><Trash2 size={16} /> Remove</button>
                  </div>
                ) : (
                  <label>
                    <ImagePlus size={22} />
                    <span>Add an image</span>
                    <small>JPEG, PNG or WebP · up to 10 MB</small>
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp"
                      onChange={(event) => chooseImage(event.target.files?.[0])}
                    />
                  </label>
                )}
              </div>

              {!online && <p className="assistant-error"><AlertCircle size={16} /> Reconnect to analyse or approve actions.</p>}
              {inputError && <p className="assistant-error"><AlertCircle size={16} /> {inputError}</p>}
              {analyse.isError && <p className="assistant-error"><AlertCircle size={16} /> Analysis failed. Your input is still here to retry.</p>}
              <footer className="assistant-capture__footer">
                <p className="assistant-capture__disclosure">
                  Sent to the configured AI provider for analysis. CoParent does not retain the raw
                  note or image.
                </p>
                <button
                  type="button"
                  className="assistant-button assistant-button--analyse"
                  disabled={!online || analyse.isPending || !familyId}
                  onClick={submit}
                >
                  {analyse.isPending ? <LoaderCircle className="assistant-spinner" size={18} /> : <ArrowRight size={18} />}
                  {analyse.isPending ? 'Finding actions…' : 'Next: review actions'}
                </button>
              </footer>
            </section>
            )}

            {step === 'review' && (
            <section className="assistant-review" aria-labelledby="assistant-review-title">
              <div className="assistant-review__toolbar">
                <button type="button" className="assistant-back" onClick={() => setStep('capture')}>
                  <ArrowLeft size={17} /> New input
                </button>
                <p>{selectedFamily ? `Private to you in ${selectedFamily.name}` : 'Your private batches'}</p>
              </div>
              <div className="assistant-stage__heading assistant-stage__heading--review">
                <p>Step 2 of 2</p>
                <div>
                  <h2 id="assistant-review-title">Review each proposed action</h2>
                  <span>Edit anything that is wrong, then approve or reject each card individually.</span>
                </div>
              </div>

              {recent.data && recent.data.length > 0 && (
                <div className="assistant-recents" aria-label="Recent private batches">
                  {recent.data.map((item) => (
                    <button
                      type="button"
                      key={item.id}
                      className={item.id === batchId ? 'assistant-recents__item assistant-recents__item--active' : 'assistant-recents__item'}
                      onClick={() => setBatchId(item.id)}
                    >
                      <span>{item.actionCount} action{item.actionCount === 1 ? '' : 's'}</span>
                      <time>{new Date(item.createdAt).toLocaleDateString()}</time>
                    </button>
                  ))}
                </div>
              )}

              {batch.isLoading && <p className="assistant-review__empty">Loading review cards…</p>}
              {batch.data?.status === 'NO_ACTION' && (
                <p className="assistant-review__empty">No actionable family task was found in that submission.</p>
              )}
              {batch.data?.actions.map((action) => (
                <AssistantActionCard
                  key={action.id}
                  familyId={familyId}
                  batchId={batch.data.id}
                  action={action}
                  online={online}
                  options={editorOptions}
                />
              ))}
              {!batchId && <p className="assistant-review__empty">Your review cards will appear here.</p>}
            </section>
            )}
          </div>
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
