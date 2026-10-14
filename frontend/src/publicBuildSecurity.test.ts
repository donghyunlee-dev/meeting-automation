import { describe, expect, it } from 'vitest';
import { assertAllowedPublicViteEnvironment, scanPublicBundle } from '../publicBuildSecurity';

describe('public frontend build security', () => {
  it('preserves the public API base URL and ignores non-public environment keys', () => {
    expect(() => assertAllowedPublicViteEnvironment({
      VITE_API_BASE_URL: 'https://api.example.test',
      NODE_ENV: 'production',
    })).not.toThrow();
  });

  it('rejects backend-only VITE keys without including their synthetic values in the error', () => {
    const syntheticProviderSecret = 'SYNTHETIC_PROVIDER_SECRET_CANARY_42';

    expect(() => assertAllowedPublicViteEnvironment({
      VITE_API_BASE_URL: 'https://api.example.test',
      VITE_NOTION_TOKEN: syntheticProviderSecret,
    })).toThrowError(expect.objectContaining({
      message: expect.stringContaining('VITE_NOTION_TOKEN'),
    }));

    try {
      assertAllowedPublicViteEnvironment({ VITE_NOTION_TOKEN: syntheticProviderSecret });
    } catch (error) {
      expect((error as Error).message).not.toContain(syntheticProviderSecret);
      expect((error as Error).message).toContain('PUBLIC_VITE_ALLOWLIST');
    }
  });

  it('detects synthetic provider canaries in bundle entries and reports only path and rule', () => {
    const syntheticProviderSecret = 'SYNTHETIC_PROVIDER_SECRET_CANARY_42';
    const syntheticWebhook = 'SYNTHETIC_WEBHOOK_URL_CANARY_84';
    const violations = scanPublicBundle([
      { fileName: 'assets/index.js', content: `const token = '${syntheticProviderSecret}'` },
      { fileName: 'assets/index.css', content: `/* webhook: ${syntheticWebhook} */` },
      { fileName: 'assets/safe.js', content: 'const status = "ready"' },
    ]);

    expect(violations).toEqual([
      { fileName: 'assets/index.css', rule: 'WEBHOOK_CANARY' },
      { fileName: 'assets/index.js', rule: 'PROVIDER_SECRET_CANARY' },
    ]);
    expect(JSON.stringify(violations)).not.toContain(syntheticProviderSecret);
    expect(JSON.stringify(violations)).not.toContain(syntheticWebhook);
  });

  it('passes a bundle with no synthetic canaries', () => {
    expect(scanPublicBundle([{ fileName: 'assets/index.js', content: 'export const ready = true' }])).toEqual([]);
  });
});
