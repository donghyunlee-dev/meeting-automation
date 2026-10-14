import type { OutputAsset, OutputChunk } from 'rollup';

const PUBLIC_VITE_KEYS = new Set(['VITE_API_BASE_URL']);

const SYNTHETIC_CANARIES = [
  { marker: 'SYNTHETIC_PROVIDER_SECRET_CANARY_42', rule: 'PROVIDER_SECRET_CANARY' },
  { marker: 'SYNTHETIC_WEBHOOK_URL_CANARY_84', rule: 'WEBHOOK_CANARY' },
] as const;

export interface PublicBundleEntry {
  fileName: string;
  content: string;
}

export interface PublicBundleViolation {
  fileName: string;
  rule: typeof SYNTHETIC_CANARIES[number]['rule'];
}

export function assertAllowedPublicViteEnvironment(environment: Readonly<Record<string, unknown>>): void {
  const disallowedKeys = Object.keys(environment)
    .filter((key) => key.startsWith('VITE_') && !PUBLIC_VITE_KEYS.has(key))
    .sort();

  if (disallowedKeys.length > 0) {
    throw new Error(`PUBLIC_VITE_ALLOWLIST: unsupported key(s): ${disallowedKeys.join(', ')}`);
  }
}

export function scanPublicBundle(entries: readonly PublicBundleEntry[]): PublicBundleViolation[] {
  const violations: PublicBundleViolation[] = [];
  for (const entry of entries) {
    for (const canary of SYNTHETIC_CANARIES) {
      if (entry.content.includes(canary.marker)) violations.push({ fileName: entry.fileName, rule: canary.rule });
    }
  }
  return violations.sort((left, right) => left.fileName.localeCompare(right.fileName) || left.rule.localeCompare(right.rule));
}

export function publicBundleEntries(bundle: Readonly<Record<string, OutputAsset | OutputChunk>>): PublicBundleEntry[] {
  return Object.values(bundle).map((entry) => ({
    fileName: entry.fileName,
    content: entry.type === 'chunk' ? entry.code : typeof entry.source === 'string' ? entry.source : Array.from(entry.source, (byte) => String.fromCharCode(byte)).join(''),
  }));
}
