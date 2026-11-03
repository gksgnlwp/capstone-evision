import { z } from 'zod';
import { stationSchema } from '../search/contracts';

export const operatorListSchema = z.object({ operators: z.array(z.object({
  busiId: z.string().min(1), name: z.string().min(1), stationCount: z.number().int().nonnegative(),
})) });
export const favoriteSummarySchema = z.object({ items: z.array(z.object({
  stationId: z.number().int().positive(), status: z.enum(['ACTIVE', 'CLOSED', 'NOT_FOUND']),
  station: stationSchema.nullable(),
})) });

export type FavoriteSummary = z.infer<typeof favoriteSummarySchema>['items'][number];
