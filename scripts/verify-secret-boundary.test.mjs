import assert from 'node:assert/strict';
import test from 'node:test';
import { formatDiagnostics, inspectEnvSamples, safeEnvSamplePaths } from './verify-secret-boundary.mjs';

const validFrontend = 'VITE_API_BASE_URL=http://localhost:8080\n';
const validBackend = `APP_COMPANY_ID=sfood
APP_COMPANY_NAME=SFOOD
APP_TIMEZONE=Asia/Seoul
DOCUMENT_SETTINGS_DIR=<absolute-persistent-directory>
DOCUMENT_SETTINGS_ENCRYPTION_KEY=
TRANSCRIPTION_MODEL=<configured-model-id>
MINUTES_MODEL=<configured-model-id>
EMAIL_PROVIDER=GMAIL_API
EMAIL_OAUTH_CLIENT_ID=
EMAIL_OAUTH_CLIENT_SECRET=
EMAIL_OAUTH_REFRESH_TOKEN=
EMAIL_SENDER_ADDRESS=
NOTIFICATION_PROVIDER=SLACK
ALLOWED_ORIGINS=http://localhost:5173
VERCEL_BLOB_CONTROL_URL=https://<vercel-project-domain>/api/audio-storage
RENDER_AUDIO_STORAGE_SECRET=
RENDER_AUDIO_GRANT_SECRET=
OPENAI_API_KEY=
SLACK_MEETING_WEBHOOK_URL=
SLACK_ADMIN_WEBHOOK_URL=
`;

test('accepts the documented public frontend URL and secret-free backend sample', () => {
  const result = inspectEnvSamples(validFrontend, validBackend);

  assert.equal(result.valid, true);
  assert.deepEqual(result.issues, []);
});

test('rejects frontend variables other than the public API URL', () => {
  const result = inspectEnvSamples(`${validFrontend}VITE_OPENAI_API_KEY=secret-marker\n`, validBackend);

  assert.equal(result.valid, false);
  assert.ok(result.issues.some(issue => issue.file === 'frontend/.env.example'
    && issue.key === 'VITE_OPENAI_API_KEY'));
  assert.equal(JSON.stringify(result.issues).includes('secret-marker'), false);
});

test('rejects nonempty backend credential values without returning their contents', () => {
  const result = inspectEnvSamples(validFrontend, validBackend
    .replace('OPENAI_API_KEY=\n', 'OPENAI_API_KEY=secret-marker\n')
    .replace('DOCUMENT_SETTINGS_ENCRYPTION_KEY=\n', 'DOCUMENT_SETTINGS_ENCRYPTION_KEY=secret-marker\n'));

  assert.equal(result.valid, false);
  assert.ok(result.issues.some(issue => issue.file === 'backend/.env.example'
    && issue.key === 'OPENAI_API_KEY'));
  assert.ok(result.issues.some(issue => issue.file === 'backend/.env.example'
    && issue.key === 'DOCUMENT_SETTINGS_ENCRYPTION_KEY'));
  assert.equal(JSON.stringify(result.issues).includes('secret-marker'), false);
});

test('treats EMAIL_PROVIDER as a non-secret selection but rejects credential values under EMAIL_*', () => {
  const result = inspectEnvSamples(validFrontend, validBackend.replace('EMAIL_OAUTH_CLIENT_SECRET=\n', 'EMAIL_OAUTH_CLIENT_SECRET=secret-marker\n'));

  assert.equal(result.valid, false);
  assert.ok(result.issues.some(issue => issue.key === 'EMAIL_OAUTH_CLIENT_SECRET'));
  assert.equal(JSON.stringify(result.issues).includes('EMAIL_PROVIDER'), false);
  assert.equal(JSON.stringify(result.issues).includes('secret-marker'), false);
});

test('requires the frontend API URL and flags malformed sample lines safely', () => {
  const missingFrontendUrl = inspectEnvSamples('', validBackend);
  const malformedBackend = inspectEnvSamples(validFrontend, `${validBackend}not-an-env-entry\n`);

  assert.equal(missingFrontendUrl.valid, false);
  assert.ok(missingFrontendUrl.issues.some(issue => issue.file === 'frontend/.env.example'
    && issue.key === 'VITE_API_BASE_URL'));
  assert.equal(malformedBackend.valid, false);
  assert.ok(malformedBackend.issues.some(issue => issue.file === 'backend/.env.example'
    && issue.code === 'INVALID_LINE'));
  assert.equal(JSON.stringify(malformedBackend.issues).includes('not-an-env-entry'), false);
});

test('requires the documented non-secret backend settings', () => {
  const result = inspectEnvSamples(validFrontend, validBackend.replace('DOCUMENT_SETTINGS_DIR=<absolute-persistent-directory>\n', ''));

  assert.equal(result.valid, false);
  assert.ok(result.issues.some(issue => issue.file === 'backend/.env.example'
    && issue.key === 'DOCUMENT_SETTINGS_DIR'
    && issue.code === 'MISSING_REQUIRED_KEY'));
});

test('requires the Vercel Blob control endpoint and server-only secrets', () => {
  const missingEndpoint = inspectEnvSamples(validFrontend,
    validBackend.replace('VERCEL_BLOB_CONTROL_URL=https://<vercel-project-domain>/api/audio-storage\n', ''));

  assert.equal(inspectEnvSamples(validFrontend, validBackend).valid, true);
  assert.equal(missingEndpoint.valid, false);
  assert.ok(missingEndpoint.issues.some(issue => issue.file === 'backend/.env.example'
    && issue.key === 'VERCEL_BLOB_CONTROL_URL'
    && issue.code === 'MISSING_REQUIRED_KEY'));
});

test('limits file-based inspection to the two checked-in environment samples', () => {
  assert.deepEqual(safeEnvSamplePaths(), ['frontend/.env.example', 'backend/.env.example']);
  assert.equal(safeEnvSamplePaths().some(path => path.endsWith('/.env') || path.includes('secret-store')), false);
});

test('formats scanner findings with only file path and rule, never key or matched value', () => {
  const result = inspectEnvSamples(`${validFrontend}VITE_OPENAI_API_KEY=synthetic-provider-secret-canary`, validBackend);
  const report = formatDiagnostics(result);

  assert.equal(report, 'frontend/.env.example: UNSUPPORTED_FRONTEND_KEY');
  assert.equal(report.includes('VITE_OPENAI_API_KEY'), false);
  assert.equal(report.includes('synthetic-provider-secret-canary'), false);
});
