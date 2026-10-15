import type { ObservedChartMarker, PaperChartFill } from '../api/types';
import { trimFractionZeros } from '../lib/decimal';
import { exactUnits } from './tradeWallets';

export type MarketAction = ObservedChartMarker | PaperChartFill;
export interface MarkerAnchor { x: number; y: number }
export interface MarkerPosition { action: MarketAction; point: MarkerAnchor; center: MarkerAnchor }
export interface MarkerGroup { actions: MarketAction[]; point: MarkerAnchor; center: MarkerAnchor }

export function actionKey(action: MarketAction): string {
  return action.kind === 'OBSERVED' ? action.observationId : `${action.intentId}:${action.phase}`;
}

export const trimDecimal = trimFractionZeros;

export function actionTime(time: string): string {
  const date = new Date(time);
  return Number.isNaN(date.getTime()) ? time : `${date.toISOString().slice(11, 19)} UTC`;
}

export function markerLabel(action: MarketAction): string {
  return action.kind === 'OBSERVED'
    ? `Decu ${action.side.toLowerCase()}, ${trimDecimal(exactUnits(action.solLamports, 9))} SOL at ${actionTime(action.time)}`
    : `Bot paper ${action.phase.toLowerCase()}, ${trimDecimal(action.quantity)} tokens at ${actionTime(action.time)}`;
}

/** Connected Decu pins form one stack at the current pixel scale. Paper fills stay individual. */
export function groupMarkerPositions(positions: MarkerPosition[]): MarkerGroup[] {
  const remaining = [...positions].sort((a, b) => Date.parse(a.action.time) - Date.parse(b.action.time) || a.action.slot - b.action.slot);
  const groups: MarkerGroup[] = [];
  while (remaining.length) {
    const first = remaining.shift()!;
    const members = [first];
    if (first.action.kind === 'OBSERVED' && first.action.isTrackedWallet) {
      for (let index = 0; index < members.length; index++) {
        const member = members[index]!;
        for (let candidate = remaining.length - 1; candidate >= 0; candidate--) {
          const next = remaining[candidate]!;
          if (next.action.kind === 'OBSERVED' && next.action.isTrackedWallet && Math.hypot(member.center.x - next.center.x, member.center.y - next.center.y) < 28) {
            members.push(next);
            remaining.splice(candidate, 1);
          }
        }
      }
    }
    members.sort((a, b) => Date.parse(a.action.time) - Date.parse(b.action.time) || a.action.slot - b.action.slot);
    groups.push({ actions: members.map((member) => member.action), point: first.point, center: first.center });
  }
  return groups.sort((a, b) => Date.parse(a.actions[0]!.time) - Date.parse(b.actions[0]!.time) || a.actions[0]!.slot - b.actions[0]!.slot);
}
