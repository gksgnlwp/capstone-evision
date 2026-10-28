import { config } from '../config/env';

export function EnvironmentBadge() {
  return config.environment === 'DEV' ? <span className="environment-badge" aria-label="DEV 환경">DEV</span> : null;
}
