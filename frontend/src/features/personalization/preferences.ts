import { useSyncExternalStore } from 'react';

const favoriteKey = 'evision.favoriteStationIds';
const operatorKey = 'evision.preferredOperatorIds';
export const favoriteLimit = 20;

interface Preferences { favoriteIds: number[]; operatorIds: string[]; favoritePersistent: boolean; operatorPersistent: boolean }

function readList(key: string): unknown[] {
  try {
    const parsed: unknown = JSON.parse(window.localStorage.getItem(key) || '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch { return []; }
}
function readPreferences(): Preferences {
  if (typeof window === 'undefined') return { favoriteIds: [], operatorIds: [], favoritePersistent: false, operatorPersistent: false };
  let accessible = true;
  try { window.localStorage.getItem(favoriteKey); } catch { accessible = false; }
  return {
    favoriteIds: [...new Set(readList(favoriteKey).filter((value): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0))].slice(0, favoriteLimit),
    operatorIds: [...new Set(readList(operatorKey).filter((value): value is string => typeof value === 'string' && value.length > 0 && value.length <= 40))],
    favoritePersistent: accessible,
    operatorPersistent: accessible,
  };
}

let snapshot = readPreferences();
const emptySnapshot: Preferences = { favoriteIds: [], operatorIds: [], favoritePersistent: false, operatorPersistent: false };
const listeners = new Set<() => void>();
function publish(next: Preferences) { snapshot = next; listeners.forEach(listener => listener()); }
function write(key: string, values: number[] | string[]) {
  try { window.localStorage.setItem(key, JSON.stringify(values)); return true; }
  catch { return false; }
}
function subscribe(listener: () => void) { listeners.add(listener); return () => listeners.delete(listener); }

if (typeof window !== 'undefined') {
  window.addEventListener('storage', event => {
    if (event.key === favoriteKey || event.key === operatorKey || event.key === null) publish(readPreferences());
  });
}

export function usePreferences() { return useSyncExternalStore(subscribe, () => snapshot, () => emptySnapshot); }
export function setPreferredOperators(ids: string[]) {
  const operatorIds = [...new Set(ids.filter(id => id.length > 0 && id.length <= 40))];
  const operatorPersistent = write(operatorKey, operatorIds);
  publish({ ...snapshot, operatorIds, operatorPersistent });
}
export function toggleFavorite(stationId: number): 'added' | 'removed' | 'limit' {
  if (!Number.isSafeInteger(stationId) || stationId < 1) throw new Error('충전소 번호가 올바르지 않습니다.');
  const existing = snapshot.favoriteIds;
  const stored = existing.includes(stationId);
  if (!stored && existing.length >= favoriteLimit) return 'limit';
  const favoriteIds = stored ? existing.filter(id => id !== stationId) : [...existing, stationId];
  const favoritePersistent = write(favoriteKey, favoriteIds);
  publish({ ...snapshot, favoriteIds, favoritePersistent });
  return stored ? 'removed' : 'added';
}
export function removeFavorite(stationId: number) {
  if (!snapshot.favoriteIds.includes(stationId)) return;
  const favoriteIds = snapshot.favoriteIds.filter(id => id !== stationId);
  const favoritePersistent = write(favoriteKey, favoriteIds);
  publish({ ...snapshot, favoriteIds, favoritePersistent });
}
