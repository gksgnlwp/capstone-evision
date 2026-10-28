import { Chip } from '../../components/ui';
import { chargerOptions } from './searchUtils';

export type AccessFilter = '' | 'REST_AREA' | 'IC';
interface Props { accessType: AccessFilter; chargerType: string; onAccessChange: (value: AccessFilter) => void; onChargerChange: (value: string) => void }
export function SearchFilters({ accessType, chargerType, onAccessChange, onChargerChange }: Props) {
  return <div className="search-filters">
    <div className="row wrap" role="group" aria-label="접근 유형">
      {([['', '전체'], ['REST_AREA', '휴게소'], ['IC', 'IC 인근']] as const).map(([value, label]) =>
        <Chip key={value} selected={accessType === value} onClick={() => onAccessChange(value)}>{label}</Chip>)}
    </div>
    <select aria-label="충전기 유형" value={chargerType} onChange={event => onChargerChange(event.target.value)} className="search-select">
      {chargerOptions.map(option => <option value={option.value} key={option.value}>{option.label}</option>)}
    </select>
  </div>;
}
