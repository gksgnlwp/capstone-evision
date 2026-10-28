const key = 'evision.recentDestinations';
export function readRecentDestinations(): string[] {
  try {
    const value: unknown = JSON.parse(window.localStorage.getItem(key) || '[]');
    return Array.isArray(value) ? [...new Set(value.filter((v): v is string => typeof v === 'string' && v.trim().length > 0 && v.length <= 100))].slice(0, 5) : [];
  } catch { return []; }
}
export function saveRecentDestination(destination: string) {
  const value = destination.trim().slice(0, 100);
  if (!value) return;
  try { window.localStorage.setItem(key, JSON.stringify([value, ...readRecentDestinations().filter(item => item !== value)].slice(0, 5))); }
  catch { /* 저장 제한 환경에서도 현재 탐색은 계속합니다. */ }
}
