import { beforeEach, describe, expect, it, vi } from 'vitest';
import { getSetupSnapshot } from './documentSetupApi';
import { loadStoredDocumentConnection, savePendingDocumentConnection, saveReadyDocumentConnection } from './documentConnectionStorage';

const notionRoot = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

function response(data: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify({ data }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }));
}

describe('document setup browser restoration', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('rehydrates one Backend runtime connection from browser storage', async () => {
    saveReadyDocumentConnection({
      provider: 'NOTION',
      location: { parentPageId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' },
      credentials: { token: 'synthetic-notion-secret' },
    }, notionRoot, 1);
    let statusReads = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup') && !init?.method) {
        statusReads += 1;
        return response(statusReads === 1
          ? { version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false }
          : { version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example', rootId: notionRoot }, draft: null, operation: { operationId: 'op', type: 'INITIALIZE', status: 'SUCCEEDED' }, canCreateMeeting: true });
      }
      if (path.endsWith('/drafts') && init?.method === 'POST') return response({ draftId: 'draft', revision: 1, state: 'DRAFT', version: 1 }, 201);
      if (path.endsWith('/drafts/draft/test') && init?.method === 'POST') return response({ draftId: 'draft', revision: 1, expiresAt: '2026-10-16T00:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 });
      if (path.endsWith('/drafts/draft/initialize') && init?.method === 'POST') return response({ operationId: 'op', status: 'PENDING', version: 3 }, 202);
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);

    const snapshot = await getSetupSnapshot();

    expect(snapshot.snapshot.status).toBe('READY');
    const draftBody = JSON.parse(String(fetchMock.mock.calls.find(([url]) => new URL(String(url)).pathname.endsWith('/drafts'))?.[1]?.body));
    expect(draftBody).toMatchObject({ provider: 'NOTION', reuseExistingRootId: notionRoot });
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/initialize'))).toHaveLength(1);
  });

  it('restores a verified connection saved as pending after Backend restarts before page creation finishes', async () => {
    savePendingDocumentConnection({
      provider: 'NOTION',
      location: { parentPageId: notionRoot },
      credentials: { token: 'synthetic-notion-secret' },
    });
    let configured = false;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup') && !init?.method) return response(configured
        ? { version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example', rootId: notionRoot }, draft: null, operation: { operationId: 'op', type: 'INITIALIZE', status: 'SUCCEEDED' }, canCreateMeeting: true }
        : { version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false });
      if (path.endsWith('/drafts') && init?.method === 'POST') return response({ draftId: 'draft', revision: 1, state: 'DRAFT', version: 1 }, 201);
      if (path.endsWith('/test') && init?.method === 'POST') return response({ draftId: 'draft', revision: 1, expiresAt: '2099-10-16T00:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 });
      if (path.endsWith('/initialize') && init?.method === 'POST') { configured = true; return response({ operationId: 'op', status: 'PENDING', version: 3 }, 202); }
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);

    const snapshot = await getSetupSnapshot();

    expect(snapshot.snapshot.status).toBe('READY');
    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/initialize'))).toHaveLength(1);
  });

  it('shares one restore mutation sequence across concurrent setup reads', async () => {
    saveReadyDocumentConnection({
      provider: 'NOTION',
      location: { parentPageId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' },
      credentials: { token: 'synthetic-notion-secret' },
    }, notionRoot, 1);
    let configured = false;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname;
      if (path.endsWith('/document-setup') && !init?.method) return response(configured
        ? { version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example', rootId: notionRoot }, draft: null, operation: null, canCreateMeeting: true }
        : { version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false });
      if (path.endsWith('/drafts') && init?.method === 'POST') return response({ draftId: 'draft', revision: 1, state: 'DRAFT', version: 1 }, 201);
      if (path.endsWith('/test') && init?.method === 'POST') return response({ draftId: 'draft', revision: 1, expiresAt: '2026-10-16T00:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 });
      if (path.endsWith('/initialize') && init?.method === 'POST') { configured = true; return response({ operationId: 'op', status: 'PENDING', version: 3 }, 202); }
      return Promise.reject(new Error(`Unexpected request: ${path}`));
    });
    vi.stubGlobal('fetch', fetchMock);

    await Promise.all([getSetupSnapshot(), getSetupSnapshot()]);

    expect(fetchMock.mock.calls.filter(([url]) => new URL(String(url)).pathname.endsWith('/initialize'))).toHaveLength(1);
  });

  it.each(['save', 'test', 'initialize'] as const)(
    'resumes restoration after a lost %s response',
    async (lostStage) => {
      saveReadyDocumentConnection({
        provider: 'NOTION',
        location: { parentPageId: notionRoot },
        credentials: { token: 'synthetic-notion-secret' },
      }, notionRoot, 1);
      let backendStage: 'EMPTY' | 'DRAFT' | 'TESTED' | 'INITIALIZING' | 'READY' = 'EMPTY';
      let lost = false;
      let initializingReads = 0;
      const calls = { save: 0, test: 0, initialize: 0 };
      const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input)).pathname;
        if (path.endsWith('/document-setup') && !init?.method) {
          if (backendStage === 'EMPTY') return response({ version: 0, status: 'UNCONFIGURED', active: null, draft: null, operation: null, canCreateMeeting: false });
          if (backendStage === 'READY') return response({ version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example', rootId: notionRoot }, draft: null, operation: { operationId: 'op', type: 'INITIALIZE', status: 'SUCCEEDED' }, canCreateMeeting: true });
          const currentStage = backendStage;
          if (currentStage === 'INITIALIZING' && ++initializingReads > 1) {
            backendStage = 'READY';
            return response({ version: 4, status: 'READY', active: { provider: 'NOTION', connectionVersion: 1, rootUrl: 'https://notion.example', rootId: notionRoot }, draft: null, operation: { operationId: 'op', type: 'INITIALIZE', status: 'SUCCEEDED' }, canCreateMeeting: true });
          }
          return response({
            version: currentStage === 'DRAFT' ? 1 : currentStage === 'TESTED' ? 2 : 3,
            status: 'CONFIGURING', active: null,
            draft: { draftId: 'draft', revision: 1, provider: 'NOTION', state: currentStage },
            operation: currentStage === 'INITIALIZING' ? { operationId: 'op', type: 'INITIALIZE', status: 'RUNNING' } : null,
            canCreateMeeting: false,
          });
        }
        if (path.endsWith('/drafts') && init?.method === 'POST') {
          calls.save += 1; backendStage = 'DRAFT';
          if (lostStage === 'save' && !lost) { lost = true; return Promise.reject(new Error('lost response')); }
          return response({ draftId: 'draft', revision: 1, state: 'DRAFT', version: 1 }, 201);
        }
        if (path.endsWith('/test') && init?.method === 'POST') {
          calls.test += 1; backendStage = 'TESTED';
          if (lostStage === 'test' && !lost) { lost = true; return Promise.reject(new Error('lost response')); }
          return response({ draftId: 'draft', revision: 1, expiresAt: '2099-10-16T00:10:00Z', authenticated: true, parentAccessible: true, writeCapability: 'UNVERIFIED', version: 2 });
        }
        if (path.endsWith('/initialize') && init?.method === 'POST') {
          calls.initialize += 1; backendStage = 'INITIALIZING';
          if (lostStage === 'initialize' && !lost) { lost = true; return Promise.reject(new Error('lost response')); }
          backendStage = 'READY';
          return response({ operationId: 'op', status: 'PENDING', version: 3 }, 202);
        }
        if (path.endsWith('/operations/op')) {
          backendStage = 'READY';
          return response({ operationId: 'op', type: 'INITIALIZE', status: 'SUCCEEDED', stage: 'ACTIVATE', counts: { participantsTotal: 0, participantsCopied: 0, meetingsTotal: 0, meetingsCopied: 0 }, cancelRequested: false, retryable: false, allowedActions: [], version: 4 });
        }
        return Promise.reject(new Error(`Unexpected request: ${path}`));
      });
      vi.stubGlobal('fetch', fetchMock);

      expect((await getSetupSnapshot()).snapshot.status).not.toBe('READY');
      expect((await getSetupSnapshot()).snapshot.status).toBe('READY');
      expect(calls).toEqual({ save: 1, test: 1, initialize: 1 });
    },
  );

  it('promotes a completed switch from a later route even when the wizard was unmounted', async () => {
    saveReadyDocumentConnection({
      provider: 'NOTION',
      location: { parentPageId: notionRoot },
      credentials: { token: 'synthetic-notion-secret' },
    }, notionRoot, 4);
    savePendingDocumentConnection({
      provider: 'CONFLUENCE',
      location: { baseUrl: 'https://example.atlassian.net', spaceId: '42' },
      credentials: { accountEmail: 'owner@example.test', apiToken: 'synthetic-confluence-secret' },
    });
    vi.stubGlobal('fetch', vi.fn(() => response({
      version: 18,
      status: 'READY',
      active: { provider: 'CONFLUENCE', connectionVersion: 5, rootUrl: 'https://example.atlassian.net/wiki/spaces/TEST', rootId: '42' },
      draft: null,
      operation: { operationId: 'switch-1', type: 'SWITCH', status: 'SUCCEEDED' },
      canCreateMeeting: true,
    })));

    await getSetupSnapshot();

    expect(loadStoredDocumentConnection()).toMatchObject({
      state: 'READY', rootId: '42', connectionVersion: 5,
      input: { provider: 'CONFLUENCE', reuseExistingRootId: '42' },
    });
  });
});
