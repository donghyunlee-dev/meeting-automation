import { getApiBaseUrl } from './apiBase';
import { apiFetch } from './apiFetch';
import { loadStoredDocumentConnection, saveReadyDocumentConnection, type StoredDocumentConnection } from './documentConnectionStorage';
export type DocumentProvider = 'NOTION' | 'CONFLUENCE';
export type SwitchMode = 'COPY_ALL' | 'START_EMPTY';
export type SetupStatus = 'UNCONFIGURED' | 'CONFIGURING' | 'READY' | 'SWITCHING' | 'ATTENTION_REQUIRED' | 'STORAGE_UNAVAILABLE';
export type SetupSnapshot = {
  version: number | null;
  status: SetupStatus;
  active: { provider: DocumentProvider; connectionVersion: number; rootUrl: string; rootId: string } | null;
  draft: { draftId: string; revision: number; provider: DocumentProvider; state: string } | null;
  operation: { operationId: string; type: string; status: string } | null;
  canCreateMeeting: boolean;
};
export type SetupOperation = {
  operationId: string; type: string; status: string; stage: string;
  counts: { participantsTotal: number; participantsCopied: number; meetingsTotal: number; meetingsCopied: number };
  cancelRequested: boolean; errorCode?: string; retryable: boolean; allowedActions: string[]; version: number;
  completedSteps?: string[]; currentStep?: string | null;
};
export type DraftInput = {
  provider: DocumentProvider;
  location: Record<string, string>;
  credentials: Record<string, string>;
  reuseExistingRootId?: string;
};
export type TestedDraft = { draftId: string; revision: number; expiresAt: string; authenticated: boolean; parentAccessible: boolean; writeCapability: 'VERIFIED' | 'UNVERIFIED'; version: number };

export class DocumentSetupError extends Error {
  constructor(readonly code: string, readonly status: number) { super(code); this.name = 'DocumentSetupError'; }
}

function apiBase() { return getApiBaseUrl() + '/api/v1/document-setup'; }
function newKey() { return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`; }

async function request<T>(path: string, init?: RequestInit): Promise<{ data: T; etag: string | null }> {
  let response: Response;
  try { response = await apiFetch(`${apiBase()}${path}`, { ...init, headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers } }); }
  catch { throw new DocumentSetupError('NETWORK_ERROR', 0); }
  let body: { data?: T; error?: { code?: string } } = {};
  try { body = await response.json() as typeof body; } catch { /* handled below */ }
  if (!response.ok || body.data === undefined) throw new DocumentSetupError(body.error?.code ?? 'REQUEST_FAILED', response.status);
  return { data: body.data, etag: response.headers.get('ETag') };
}

async function readSetupSnapshot(): Promise<{ snapshot: SetupSnapshot; etag: string | null }> {
  const result = await request<SetupSnapshot>('');
  return { snapshot: result.data, etag: result.etag };
}

let restorePromise: Promise<{ snapshot: SetupSnapshot; etag: string | null }> | null = null;

export async function getSetupSnapshot(): Promise<{ snapshot: SetupSnapshot; etag: string | null }> {
  const current = await readSetupSnapshot();
  const stored = loadStoredDocumentConnection();
  reconcileCompletedConnection(current.snapshot, stored);
  if (!canRestore(current.snapshot, stored)) return current;
  if (!restorePromise) {
    restorePromise = restoreStoredConnection(current, stored).finally(() => { restorePromise = null; });
  }
  try {
    const restored = await restorePromise;
    reconcileCompletedConnection(restored.snapshot, loadStoredDocumentConnection());
    return restored;
  } catch {
    return current;
  }
}

function canRestore(snapshot: SetupSnapshot, stored: StoredDocumentConnection | null): stored is StoredDocumentConnection {
  if (!stored || snapshot.active !== null) return false;
  if (snapshot.status === 'UNCONFIGURED') return true;
  return stored.state === 'READY' && snapshot.status === 'CONFIGURING'
    && snapshot.draft?.provider === stored.input.provider;
}

function reconcileCompletedConnection(snapshot: SetupSnapshot, stored: StoredDocumentConnection | null): void {
  if (!stored || snapshot.status !== 'READY' || !snapshot.active || snapshot.draft !== null
    || snapshot.operation?.status !== 'SUCCEEDED') return;
  const candidate = stored.state === 'PENDING' ? stored.input : stored.pendingInput;
  if (!candidate || candidate.provider !== snapshot.active.provider) return;
  if (stored.state === 'READY' && stored.rootId === snapshot.active.rootId
    && stored.connectionVersion === snapshot.active.connectionVersion) return;
  try { saveReadyDocumentConnection(candidate, snapshot.active.rootId, snapshot.active.connectionVersion); }
  catch { /* Keep the existing browser recovery state if the response is invalid. */ }
}

async function restoreStoredConnection(
  current: { snapshot: SetupSnapshot; etag: string | null },
  stored: StoredDocumentConnection,
): Promise<{ snapshot: SetupSnapshot; etag: string | null }> {
  let version = current.snapshot.version;
  if (version === null) return current;
  let snapshot = current.snapshot;
  let draft: SetupSnapshot['draft'] = snapshot.draft;
  if (snapshot.status === 'UNCONFIGURED') {
    const saved = (await saveSetupDraft(stored.input, version,
      `browser-restore-save-${stored.connectionVersion}-${stored.rootId}`)).data;
    version = saved.version;
    draft = { draftId: saved.draftId, revision: saved.revision, provider: stored.input.provider, state: 'DRAFT' };
    snapshot = { ...snapshot, version, status: 'CONFIGURING', draft };
  }
  if (!draft || draft.provider !== stored.input.provider) return { snapshot, etag: current.etag };
  if (draft.state === 'DRAFT') {
    const tested = (await testSetupDraft(draft.draftId, draft.revision, version,
      `browser-restore-test-${draft.draftId}-${draft.revision}`)).data;
    version = tested.version;
    draft = { ...draft, state: 'TESTED' };
    snapshot = { ...snapshot, version, draft };
  }
  if (draft.state === 'TESTED') {
    const initialized = await initializeSetup(draft.draftId, draft.revision, version,
      `browser-restore-initialize-${draft.draftId}-${draft.revision}`);
    version = initialized.data.version;
    snapshot = { ...snapshot, version, draft: { ...draft, state: 'INITIALIZING' },
      operation: { operationId: initialized.data.operationId, type: 'INITIALIZE', status: initialized.data.status } };
  }
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const refreshed = await readSetupSnapshot();
    if (refreshed.snapshot.status === 'READY' || ['ATTENTION_REQUIRED', 'STORAGE_UNAVAILABLE'].includes(refreshed.snapshot.status)) {
      return refreshed;
    }
    if (attempt < 29) await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new DocumentSetupError('DOCUMENT_RESTORE_TIMEOUT', 0);
}
export async function getSetupOperation(operationId: string): Promise<SetupOperation> {
  return (await request<SetupOperation>(`/operations/${encodeURIComponent(operationId)}`)).data;
}
export async function saveSetupDraft(input: DraftInput, version: number, key = newKey()) {
  return request<{ draftId: string; revision: number; state: 'DRAFT'; version: number }>('/drafts', { method: 'POST', headers: mutationHeaders(version, key), body: JSON.stringify(input) });
}
export async function testSetupDraft(draftId: string, revision: number, version: number, key = newKey()) {
  return request<TestedDraft>(`/drafts/${encodeURIComponent(draftId)}/test`, { method: 'POST', headers: mutationHeaders(version, key), body: JSON.stringify({ draftRevision: revision }) });
}
export async function initializeSetup(draftId: string, revision: number, version: number, key = newKey()) {
  return request<{ operationId: string; status: 'PENDING'; version: number }>(`/drafts/${encodeURIComponent(draftId)}/initialize`, { method: 'POST', headers: mutationHeaders(version, key), body: JSON.stringify({ draftRevision: revision, confirmStructure: true }) });
}
export async function switchSetup(draftId: string, draftRevision: number, mode: SwitchMode, version: number, key = newKey()) {
  return request<{ operationId: string; status: string; version: number }>('/switch', { method: 'POST', headers: mutationHeaders(version, key), body: JSON.stringify({ draftId, draftRevision, mode, confirmSourcePreserved: true }) });
}
export async function actOnSetupOperation(operationId: string, action: 'RETRY' | 'CANCEL', version: number, key = newKey()) {
  return request<{ operationId: string; status: string; version: number }>(`/operations/${encodeURIComponent(operationId)}/${action.toLowerCase()}`, { method: 'POST', headers: mutationHeaders(version, key), body: '{}' });
}
function mutationHeaders(version: number, key: string) {
  return { 'If-Match': `"${version}"`, 'Idempotency-Key': key, 'X-Document-Setup-Request': 'true' };
}
