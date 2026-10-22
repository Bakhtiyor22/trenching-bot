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

/** Pixels between a marker's pin and the card it opens. */
export const ACTION_CARD_GAP = 26;

/** Pixels an action card keeps from the plot's left and right edges. */
const ACTION_CARD_MARGIN = 8;

export interface CardSize {
  width: number;
  height: number;
}

/**
 * Where an action card goes inside a plot: below a marker in the plot's upper half and above one in its lower half, centred
 * on it. A card too tall for either goes beside the marker, on the side with room, so it never hides the marker it describes.
 * It never starts above the plot's top, where the page header would cover it. An unmeasured plot (height 0) puts it below.
 */
export function actionCardPosition(anchor: MarkerAnchor, card: CardSize, plot: CardSize): MarkerAnchor {
  const centred = Math.max(ACTION_CARD_MARGIN, Math.min(anchor.x - card.width / 2, plot.width - card.width - ACTION_CARD_MARGIN));
  const below = anchor.y + ACTION_CARD_GAP;
  if (plot.height <= 0) return { x: centred, y: below };
  const above = anchor.y - ACTION_CARD_GAP - card.height;
  const preferred = anchor.y < plot.height / 2 ? below : above;
  const fits = (top: number) => top >= 0 && top + card.height <= plot.height;
  if (fits(preferred)) return { x: centred, y: preferred };
  const other = preferred === below ? above : below;
  if (fits(other)) return { x: centred, y: other };
  const top = Math.max(0, Math.min(anchor.y - card.height / 2, plot.height - card.height));
  const right = anchor.x + ACTION_CARD_GAP;
  const left = anchor.x - ACTION_CARD_GAP - card.width;
  if (right + card.width <= plot.width - ACTION_CARD_MARGIN) return { x: right, y: top };
  if (left >= ACTION_CARD_MARGIN) return { x: left, y: top };
  return { x: centred, y: Math.max(0, Math.min(preferred, plot.height - card.height)) };
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
