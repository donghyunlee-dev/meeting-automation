const PRODUCTION_API_BASE_URL = 'https://meeting-automation-4419.onrender.com';

export function getApiBaseUrl(): string {
  const baseUrl = import.meta.env.MODE === 'test'
    ? import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'
    : import.meta.env.DEV
      ? ''
      : import.meta.env.VITE_API_BASE_URL || PRODUCTION_API_BASE_URL;

  return baseUrl.replace(/\/$/, '');
}
