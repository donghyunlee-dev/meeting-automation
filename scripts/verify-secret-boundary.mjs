import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const FRONTEND_ALLOWED = new Map([['VITE_API_BASE_URL', 'http://localhost:8080']]);
const BACKEND_REQUIRED = [
  'APP_COMPANY_ID',
  'APP_COMPANY_NAME',
  'APP_TIMEZONE',
  'DOCUMENT_PROVIDER',
  'DOCUMENT_ROOT_ID',
  'TRANSCRIPTION_MODEL',
  'MINUTES_MODEL',
  'EMAIL_PROVIDER',
  'NOTIFICATION_PROVIDER',
  'ALLOWED_ORIGINS',
  'TEMP_AUDIO_DIR',
  'NOTION_TOKEN',
  'CONFLUENCE_AUTH_TOKEN',
  'OPENAI_API_KEY',
  'SLACK_MEETING_WEBHOOK_URL',
  'SLACK_ADMIN_WEBHOOK_URL'
];

function parseSample(content, file, issues) {
  const entries = [];
  const seen = new Set();

  content.replace(/^\uFEFF/, '').split(/\r?\n/).forEach((line, index) => {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#')) return;

    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(trimmed);
    if (!match) {
      issues.push({ file, code: 'INVALID_LINE', line: index + 1 });
      return;
    }

    const [, key, value] = match;
    if (seen.has(key)) issues.push({ file, key, code: 'DUPLICATE_KEY' });
    seen.add(key);
    entries.push({ file, key, value: value.trim() });
  });

  return entries;
}

export function inspectEnvSamples(frontendContent, backendContent) {
  const issues = [];
  const frontendEntries = parseSample(frontendContent, 'frontend/.env.example', issues);
  const backendEntries = parseSample(backendContent, 'backend/.env.example', issues);
  const frontendKeys = new Set(frontendEntries.map(entry => entry.key));
  const backendKeys = new Set(backendEntries.map(entry => entry.key));

  for (const [key, expectedValue] of FRONTEND_ALLOWED) {
    if (!frontendKeys.has(key)) {
      issues.push({ file: 'frontend/.env.example', key, code: 'MISSING_REQUIRED_KEY' });
    }
    for (const entry of frontendEntries.filter(candidate => candidate.key === key)) {
      if (entry.value !== expectedValue) {
        issues.push({ file: entry.file, key, code: 'INVALID_PUBLIC_VALUE' });
      }
    }
  }

  for (const entry of frontendEntries) {
    if (!FRONTEND_ALLOWED.has(entry.key)) {
      issues.push({ file: entry.file, key: entry.key, code: 'UNSUPPORTED_FRONTEND_KEY' });
    }
  }

  for (const key of BACKEND_REQUIRED) {
    if (!backendKeys.has(key)) {
      issues.push({ file: 'backend/.env.example', key, code: 'MISSING_REQUIRED_KEY' });
    }
  }

  for (const entry of backendEntries) {
    const isCredential = entry.key === 'NOTION_TOKEN'
      || entry.key === 'CONFLUENCE_AUTH_TOKEN'
      || entry.key === 'OPENAI_API_KEY'
      || entry.key === 'SLACK_MEETING_WEBHOOK_URL'
      || entry.key === 'SLACK_ADMIN_WEBHOOK_URL'
      || (entry.key.startsWith('EMAIL_') && entry.key !== 'EMAIL_PROVIDER');
    if (isCredential && entry.value !== '') {
      issues.push({ file: entry.file, key: entry.key, code: 'NONEMPTY_CREDENTIAL' });
    }
  }

  return { valid: issues.length === 0, issues };
}

async function main() {
  const [frontendContent, backendContent] = await Promise.all([
    readFile(resolve(ROOT, 'frontend/.env.example'), 'utf8'),
    readFile(resolve(ROOT, 'backend/.env.example'), 'utf8')
  ]);
  const result = inspectEnvSamples(frontendContent, backendContent);

  if (!result.valid) {
    for (const issue of result.issues) {
      const location = issue.line ? `${issue.file}:${issue.line}` : issue.file;
      const key = issue.key ? ` (${issue.key})` : '';
      console.error(`${location}: ${issue.code}${key}`);
    }
    process.exitCode = 1;
    return;
  }

  console.log('Secret boundary verification passed for frontend/.env.example and backend/.env.example.');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {
    console.error('Secret boundary verification could not read the environment sample files.');
    process.exitCode = 1;
  });
}
