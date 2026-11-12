import { describe, expect, it } from 'vitest';
import { recordsTrades } from './datasetSchema';

describe('recordsTrades', () => {
  it('is false for schema 3 recordings and true from schema 4 on', () => {
    expect(recordsTrades(3)).toBe(false);
    expect([4, 5, 6].map(recordsTrades)).toEqual([true, true, true]);
  });
});
