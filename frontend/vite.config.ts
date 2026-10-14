import { loadEnv, defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { assertAllowedPublicViteEnvironment, publicBundleEntries, scanPublicBundle } from './publicBuildSecurity';

function publicBuildSecurityPlugin(): Plugin {
  return {
    name: 'public-build-security',
    apply: 'build',
    generateBundle(_options, bundle) {
      const violations = scanPublicBundle(publicBundleEntries(bundle));
      if (violations.length > 0) {
        const summary = violations.map(({ fileName, rule }) => `${fileName} [${rule}]`).join(', ');
        this.error(`PUBLIC_BUNDLE_CANARY: ${summary}`);
      }
    },
  };
}

export default defineConfig(({ mode }) => {
  const loadedEnvironment = loadEnv(mode, '.', 'VITE_');
  assertAllowedPublicViteEnvironment(loadedEnvironment);

  return {
    plugins: [react(), publicBuildSecurityPlugin()],
  };
});
