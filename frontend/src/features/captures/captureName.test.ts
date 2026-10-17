import { describe, expect, it } from 'vitest';
import { generatedName } from './captureName';

describe('capture name preview', () => {
  it('preserves the wallet label, short token and local creation minute', () => {
    expect(generatedName('Decu', '7mup123456pump', new Date(2026, 9, 16, 20, 13))).toBe(
      'Decu · 7mup…pump · 16 Oct 20:13',
    );
  });
});
