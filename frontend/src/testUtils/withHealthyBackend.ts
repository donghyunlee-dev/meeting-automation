export function withHealthyBackend(fetchImpl: unknown): typeof fetch {
  const fetcher = fetchImpl as typeof fetch;
  return ((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const pathname = new URL(url, window.location.href).pathname;
    if (pathname === '/api/v1/system/health') {
      return Promise.resolve(new Response(JSON.stringify({ data: { status: 'UP' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }));
    }
    return fetcher(input, init);
  }) as typeof fetch;
}
