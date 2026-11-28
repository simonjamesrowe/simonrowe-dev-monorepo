import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiClient } from '../../lib/api/client';
import type {
  DocumentDetail,
  DocumentDetails,
  DocumentProposal,
  DocumentSection,
  FamilyDocument,
  SchoolOffer,
  VersionDetails,
} from '../../types/documents';

export const documentKeys = {
  all: ['documents'] as const,
  list: (familyId: string, archived: boolean) =>
    [...documentKeys.all, 'list', familyId, archived ? 'archived' : 'current'] as const,
  lists: (familyId: string) => [...documentKeys.all, 'list', familyId] as const,
  detail: (familyId: string, id: string) => [...documentKeys.all, 'detail', familyId, id] as const,
  offers: (familyId: string) => [...documentKeys.all, 'school-offers', familyId] as const,
};

const base = (familyId: string) => `/families/${familyId}/documents`;

/**
 * Multipart bodies leave Content-Type unset so the browser writes the boundary. The JSON part
 * is a Blob typed application/json, which is what lets Spring bind it as a @RequestPart object.
 */
const MULTIPART = { headers: { 'Content-Type': undefined } };

const jsonPart = (value: unknown) => new Blob([JSON.stringify(value)], { type: 'application/json' });

function filesForm(files: File[], details?: unknown): FormData {
  const form = new FormData();
  if (details !== undefined) form.append('details', jsonPart(details));
  files.forEach((file) => form.append('files', file, file.name));
  return form;
}

/** Unarchived documents, or only archived ones with `archived`. */
export function useDocuments(familyId: string | undefined, archived = false) {
  return useQuery({
    queryKey: documentKeys.list(familyId!, archived),
    queryFn: async () =>
      (await apiClient.get<FamilyDocument[]>(base(familyId!), { params: { archived } })).data,
    enabled: !!familyId,
  });
}

/** One document with its history, archived or not. */
export function useDocument(familyId: string | undefined, id: string | null | undefined) {
  return useQuery({
    queryKey: documentKeys.detail(familyId!, id!),
    queryFn: async () => (await apiClient.get<DocumentDetail>(`${base(familyId!)}/${id}`)).data,
    enabled: !!familyId && !!id,
  });
}

/** Letters and report prompts from school waiting for a decision, newest first. */
export function useSchoolOffers(familyId: string | undefined) {
  return useQuery({
    queryKey: documentKeys.offers(familyId!),
    queryFn: async () =>
      (await apiClient.get<SchoolOffer[]>(`${base(familyId!)}/school-offers`)).data,
    enabled: !!familyId,
  });
}

/** Every document write can move both lists, the open drawer and (for a report) the offers. */
export function invalidateDocuments(
  queryClient: ReturnType<typeof useQueryClient>,
  familyId: string,
) {
  queryClient.invalidateQueries({ queryKey: documentKeys.lists(familyId) });
  queryClient.invalidateQueries({ queryKey: [...documentKeys.all, 'detail', familyId] });
  queryClient.invalidateQueries({ queryKey: documentKeys.offers(familyId) });
}

function useDocumentMutation<TVariables extends { familyId: string }, TResult>(
  mutationFn: (variables: TVariables) => Promise<TResult>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    // Settled, not success: a 409 means the parent was looking at an old copy, so reload it.
    onSettled: (_data, _error, variables) => invalidateDocuments(queryClient, variables.familyId),
  });
}

export function useCreateDocument() {
  return useDocumentMutation(
    async ({ familyId, details, files }: { familyId: string; details: DocumentDetails; files: File[] }) =>
      (await apiClient.post<FamilyDocument>(base(familyId), filesForm(files, details), MULTIPART)).data,
  );
}

export function useUpdateDocument() {
  return useDocumentMutation(
    async ({
      familyId,
      id,
      version,
      details,
    }: {
      familyId: string;
      id: string;
      version: number;
      details: DocumentDetails;
    }) => {
      // schoolOfferId belongs to create alone, so it is not sent.
      const body = {
        childIds: details.childIds,
        section: details.section,
        title: details.title,
        datedOn: details.datedOn,
        dueOn: details.dueOn,
        dueKind: details.dueKind,
        note: details.note,
        version,
      };
      return (await apiClient.put<FamilyDocument>(`${base(familyId)}/${id}`, body)).data;
    },
  );
}

export function useAddDocumentVersion() {
  return useDocumentMutation(
    async ({
      familyId,
      id,
      version,
      details,
      files,
    }: {
      familyId: string;
      id: string;
      version: number;
      details: VersionDetails;
      files: File[];
    }) =>
      (
        await apiClient.post<FamilyDocument>(
          `${base(familyId)}/${id}/versions`,
          filesForm(files, { ...details, version }),
          MULTIPART,
        )
      ).data,
  );
}

export function useArchiveDocument() {
  return useDocumentMutation(
    async ({ familyId, id, version, restore = false }: {
      familyId: string;
      id: string;
      version: number;
      restore?: boolean;
    }) =>
      (
        await apiClient.post<FamilyDocument>(`${base(familyId)}/${id}/${restore ? 'restore' : 'archive'}`, {
          version,
        })
      ).data,
  );
}

/** Reads an upload and proposes the form's values. Stores nothing; 503 when unavailable. */
export function useReadDocument() {
  return useMutation({
    mutationFn: async ({ familyId, files }: { familyId: string; files: File[] }) =>
      (await apiClient.post<DocumentProposal>(`${base(familyId)}/read`, filesForm(files), MULTIPART)).data,
  });
}

export function useKeepSchoolOffer() {
  return useDocumentMutation(
    async ({
      familyId,
      offerId,
      childIds,
      section,
      title,
    }: {
      familyId: string;
      offerId: string;
      childIds: string[];
      section: DocumentSection;
      title: string;
    }) =>
      (
        await apiClient.post<FamilyDocument>(`${base(familyId)}/school-offers/${offerId}/keep`, {
          childIds,
          section,
          title,
        })
      ).data,
  );
}

export function useDismissSchoolOffer() {
  return useDocumentMutation(async ({ familyId, offerId }: { familyId: string; offerId: string }) => {
    await apiClient.post(`${base(familyId)}/school-offers/${offerId}/dismiss`);
    return offerId;
  });
}

/**
 * A stored file's bytes, fetched with the bearer token because an <img src> cannot send one. The
 * caller makes an object URL and revokes it when done (see useDocumentFileUrl).
 */
export async function fetchDocumentFile(
  familyId: string,
  documentId: string,
  fileId: string,
  download = false,
): Promise<Blob> {
  const { data } = await apiClient.get<Blob>(`${base(familyId)}/${documentId}/files/${fileId}`, {
    responseType: 'blob',
    params: download ? { download: true } : undefined,
  });
  return data;
}
