import type { MockHandler } from '../../services/api/mockClient';
import { sampleStations } from '../search/searchFixtures';

const names: Record<string, string> = { ME: '환경부', ST: 'SK일렉링크', CV: '채비' };

export const personalizationHandlers: Record<string, MockHandler> = {
  'GET operators': () => ({ operators: Object.entries(sampleStations.reduce<Record<string, number>>((counts, station) => {
    if (station.busiId) counts[station.busiId] = (counts[station.busiId] || 0) + 1;
    return counts;
  }, {})).map(([busiId, stationCount]) => ({ busiId, name: names[busiId] || busiId, stationCount }))
    .sort((a, b) => b.stationCount - a.stationCount || a.busiId.localeCompare(b.busiId)) }),
  'GET stations/summaries': ({ query }) => {
    const ids = String(query?.ids || '').split(',').map(Number);
    return { items: ids.map(stationId => {
      const station = sampleStations.find(item => item.stationId === stationId);
      return { stationId, status: station ? 'ACTIVE' : 'NOT_FOUND', station: station ? { ...station } : null };
    }) };
  },
};
