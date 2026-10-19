import type { DraftInput } from './documentSetupApi';

export const DOCUMENT_CONNECTION_STORAGE_KEY = 'meeting-automation.document-connection.v1';

export type StoredDocumentConnection = {
  schemaVersion: 1;
  state: 'PENDING' | 'READY';
  input: DraftInput;
  rootId?: string;
  connectionVersion?: number;
  pendingInput?: DraftInput;
};

export function loadStoredDocumentConnection(): StoredDocumentConnection | null {
  try {
    const value = localStorage.getItem(DOCUMENT_CONNECTION_STORAGE_KEY);
    if (!value) return null;
    const parsed = JSON.parse(value) as unknown;
    return isStoredConnection(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export function savePendingDocumentConnection(input: DraftInput): void {
  const previous = loadStoredDocumentConnection();
  if (previous?.state === 'READY') {
    write({ ...previous, pendingInput: copyInput(input) });
    return;
  }
  write({ schemaVersion: 1, state: 'PENDING', input: copyInput(input) });
}

export function saveReadyDocumentConnection(
  inputOrRootId: DraftInput | string,
  rootIdOrConnectionVersion: string | number,
  connectionVersion?: number,
): void {
  const previous = loadStoredDocumentConnection();
  const input = typeof inputOrRootId === 'string'
    ? previous?.state === 'READY' ? previous.pendingInput ?? previous.input : previous?.input
    : inputOrRootId;
  const rootId = typeof inputOrRootId === 'string' ? inputOrRootId : rootIdOrConnectionVersion;
  const version = typeof inputOrRootId === 'string' ? rootIdOrConnectionVersion : connectionVersion;
  if (!input || typeof rootId !== 'string' || !rootId.trim() || !validRootId(input.provider, rootId)
    || typeof version !== 'number' || !Number.isInteger(version) || version < 1) {
    throw new Error('A tested document connection, root ID, and connection version are required');
  }
  write({
    schemaVersion: 1,
    state: 'READY',
    input: copyInput({ ...input, reuseExistingRootId: rootId }),
    rootId,
    connectionVersion: version,
  });
}

function write(value: StoredDocumentConnection): void {
  localStorage.setItem(DOCUMENT_CONNECTION_STORAGE_KEY, JSON.stringify(value));
}

function copyInput(input: DraftInput): DraftInput {
  return {
    provider: input.provider,
    location: { ...input.location },
    credentials: { ...input.credentials },
    ...(input.reuseExistingRootId ? { reuseExistingRootId: input.reuseExistingRootId } : {}),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringRecord(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((item) => typeof item === 'string');
}

function isInput(value: unknown): value is DraftInput {
  if (!isRecord(value) || !stringRecord(value.location) || !stringRecord(value.credentials)) return false;
  if (value.reuseExistingRootId !== undefined && (typeof value.reuseExistingRootId !== 'string' || !value.reuseExistingRootId.trim())) return false;
  if (value.provider === 'NOTION') {
    return Boolean(value.location.parentPageId?.trim() && value.credentials.token?.trim());
  }
  if (value.provider === 'CONFLUENCE') {
    return Boolean(value.location.baseUrl?.trim() && value.location.spaceId?.trim()
      && value.credentials.accountEmail?.trim() && value.credentials.apiToken?.trim());
  }
  return false;
}

function validRootId(provider: DraftInput['provider'], rootId: string): boolean {
  return provider === 'NOTION'
    ? /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(rootId)
    : /^[1-9][0-9]{0,19}$/.test(rootId);
}

function isStoredConnection(value: unknown): value is StoredDocumentConnection {
  if (!isRecord(value) || value.schemaVersion !== 1 || !isInput(value.input)) return false;
  if (value.state === 'PENDING') {
    return value.rootId === undefined && value.connectionVersion === undefined && value.pendingInput === undefined;
  }
  return value.state === 'READY'
    && typeof value.rootId === 'string' && Boolean(value.rootId.trim())
    && validRootId(value.input.provider, value.rootId)
    && value.input.reuseExistingRootId === value.rootId
    && (value.pendingInput === undefined || isInput(value.pendingInput))
    && Number.isInteger(value.connectionVersion) && Number(value.connectionVersion) >= 1;
}
