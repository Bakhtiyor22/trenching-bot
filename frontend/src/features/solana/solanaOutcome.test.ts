import { describe, expect, it } from 'vitest';
import {
  clusterLabel,
  isKnownOutcome,
  outcomeAlertColor,
  outcomeLabel,
  outcomeTone,
  toneGlyph,
} from './solanaOutcome';

describe('solana outcomes', () => {
  it('maps outcomes to tones without red for not configured', () => {
    expect(outcomeTone('OK')).toBe('success');
    expect(outcomeTone('RATE_LIMITED')).toBe('attention');
    expect(outcomeTone('RPC_ERROR')).toBe('failure');
    expect(outcomeTone('NOT_CONFIGURED')).toBe('neutral');
    expect(outcomeAlertColor('TIMEOUT')).toBe('orange');
    expect(outcomeAlertColor('NOT_CONFIGURED')).toBe('gray');
    expect(toneGlyph('failure')).toBe('✕');
  });

  it('puts unknown outcomes and clusters into words', () => {
    expect(outcomeLabel('TIMEOUT')).toBe('Timed out');
    expect(isKnownOutcome('NODE_BEHIND')).toBe(false);
    expect(outcomeLabel('NODE_BEHIND')).toBe('Node behind');
    expect(clusterLabel('mainnet-beta')).toBe('Mainnet');
    expect(clusterLabel('devnet')).toBe('Devnet');
    expect(clusterLabel('local-validator')).toBe('Local validator');
  });
});
