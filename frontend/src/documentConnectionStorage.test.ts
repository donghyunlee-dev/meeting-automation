import { beforeEach, describe, expect, it } from 'vitest';
import {
  loadStoredDocumentConnection,
  savePendingDocumentConnection,
  saveReadyDocumentConnection,
} from './documentConnectionStorage';

const notion = {
  provider: 'NOTION' as const,
  location: { parentPageId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' },
  credentials: { token: 'synthetic-notion-secret' },
};
const notionRoot = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';

describe('browser document connection storage', () => {
  beforeEach(() => localStorage.clear());

  it('stores a versioned pending connection after a successful connection test', () => {
    savePendingDocumentConnection(notion);

    expect(loadStoredDocumentConnection()).toEqual({
      schemaVersion: 1,
      state: 'PENDING',
      input: notion,
    });
  });

  it('marks the same connection ready with a reusable root identifier', () => {
    savePendingDocumentConnection(notion);
    saveReadyDocumentConnection(notionRoot, 4);

    expect(loadStoredDocumentConnection()).toEqual({
      schemaVersion: 1,
      state: 'READY',
      input: { ...notion, reuseExistingRootId: notionRoot },
      rootId: notionRoot,
      connectionVersion: 4,
    });
  });

  it('keeps the active connection restorable while a replacement is pending', () => {
    const confluence = {
      provider: 'CONFLUENCE' as const,
      location: { baseUrl: 'https://example.atlassian.net', spaceId: '42' },
      credentials: { accountEmail: 'owner@example.test', apiToken: 'synthetic-confluence-secret' },
    };
    saveReadyDocumentConnection(notion, notionRoot, 4);

    savePendingDocumentConnection(confluence);

    expect(loadStoredDocumentConnection()).toEqual({
      schemaVersion: 1,
      state: 'READY',
      input: { ...notion, reuseExistingRootId: notionRoot },
      rootId: notionRoot,
      connectionVersion: 4,
      pendingInput: confluence,
    });

    saveReadyDocumentConnection('42', 5);
    expect(loadStoredDocumentConnection()).toEqual({
      schemaVersion: 1,
      state: 'READY',
      input: { ...confluence, reuseExistingRootId: '42' },
      rootId: '42',
      connectionVersion: 5,
    });
  });

  it.each([
    {
      schemaVersion: 1,
      state: 'READY',
      input: notion,
      rootId: notionRoot,
      connectionVersion: 1,
    },
    {
      schemaVersion: 1,
      state: 'READY',
      input: { ...notion, reuseExistingRootId: 'ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee' },
      rootId: notionRoot,
      connectionVersion: 1,
    },
    {
      schemaVersion: 1,
      state: 'READY',
      input: { ...notion, reuseExistingRootId: 'not-a-notion-page-id' },
      rootId: 'not-a-notion-page-id',
      connectionVersion: 1,
    },
  ])('rejects inconsistent READY data: %#', (value) => {
    localStorage.setItem('meeting-automation.document-connection.v1', JSON.stringify(value));

    expect(loadStoredDocumentConnection()).toBeNull();
  });

  it.each([
    'not json',
    '{}',
    '{"schemaVersion":2,"state":"READY"}',
    '{"schemaVersion":1,"state":"READY","input":{"provider":"NOTION","location":{},"credentials":{}}}',
  ])('ignores an invalid stored document without throwing: %s', (value) => {
    localStorage.setItem('meeting-automation.document-connection.v1', value);

    expect(loadStoredDocumentConnection()).toBeNull();
  });
});
