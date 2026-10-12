export type DocumentProvider = 'NOTION' | 'CONFLUENCE';
export type SetupStatus = 'UNCONFIGURED' | 'CONFIGURING' | 'READY' | 'SWITCHING' | 'ATTENTION_REQUIRED' | 'STORAGE_UNAVAILABLE';
export type SetupSnapshot = {
  version: number | null;
  status: SetupStatus;
  active: { provider: DocumentProvider; connectionVersion: number; rootUrl: string } | null;
  draft: { draftId: string; revision: number; provider: DocumentProvider; state: string } | null;
  operation: { operationId: string; type: string; status: string } | null;
  canCreateMeeting: boolean;
};
export type SetupOperation = {
  operationId: string; type: string; status: string; stage: string;
  counts: { participantsTotal: number; participantsCopied: number; meetingsTotal: number; meetingsCopied: number };
  cancelRequested: boolean; errorCode?: string; retryable: boolean; allowedActions: string[]; version: number;
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

function apiBase() { return (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '') + '/api/v1/document-setup'; }
function newKey() { return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`; }

async function request<T>(path: string, init?: RequestInit): Promise<{ data: T; etag: string | null }> {
  let response: Response;
  try { response = await fetch(`${apiBase()}${path}`, { ...init, headers: { Accept: 'application/json', ...(init?.body ? { 'Content-Type': 'application/json' } : {}), ...init?.headers } }); }
  catch { throw new DocumentSetupError('NETWORK_ERROR', 0); }
  let body: { data?: T; error?: { code?: string } } = {};
  try { body = await response.json() as typeof body; } catch { /* handled below */ }
  if (!response.ok || body.data === undefined) throw new DocumentSetupError(body.error?.code ?? 'REQUEST_FAILED', response.status);
  return { data: body.data, etag: response.headers.get('ETag') };
}

export async function getSetupSnapshot(): Promise<{ snapshot: SetupSnapshot; etag: string | null }> {
  const result = await request<SetupSnapshot>('');
  return { snapshot: result.data, etag: result.etag };
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
function mutationHeaders(version: number, key: string) {
  return { 'If-Match': `"${version}"`, 'Idempotency-Key': key, 'X-Document-Setup-Request': 'true' };
}
