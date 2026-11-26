import type { RpcAccount, RpcAccountName, RpcAccounts } from '../../api/types';
import type { StatusTone } from '../../components/StatusChip';
import { formatCount, formatDay, humanizeCode } from '../../lib/format';

/**
 * At or below this share of the limit the credits read as low. The backend's own threshold
 * (`trenching.solana.usage.low-balance-percent`, default 10) is not in the response, so this mirrors its default.
 */
export const LOW_BALANCE_PERCENT = 10;

const DAY_MS = 86_400_000;
const ACCOUNT_ORDER: readonly string[] = ['primary', 'backup'] satisfies RpcAccountName[];
const ACCOUNT_LABELS: Record<string, string> = { primary: 'Primary', backup: 'Backup' };

/** "primary" → "Primary"; a name this version does not know is turned into words. */
export function accountLabel(name: string): string {
  return ACCOUNT_LABELS[name] ?? humanizeCode(name);
}

/** The environment variable that holds an account's RPC URL on the server. Only its name is ever shown. */
export function rpcUrlVariable(name: string): string {
  return name === 'backup' ? 'SOLANA_RPC_BACKUP_URL' : 'SOLANA_RPC_URL';
}

/** The environment variable that holds an account's Helius project id on the server. Only its name is ever shown. */
export function projectIdVariable(name: string): string {
  return name === 'backup' ? 'HELIUS_BACKUP_PROJECT_ID' : 'HELIUS_PROJECT_ID';
}

export interface AccountStateChip {
  label: string;
  tone: StatusTone;
  glyph: string;
  /** False for a state this version has no words for; its code is then shown beside the chip. */
  known: boolean;
}

const STATE_CHIPS: Record<string, Omit<AccountStateChip, 'known'>> = {
  ACTIVE: { label: 'In use', tone: 'success', glyph: '✓' },
  STANDBY: { label: 'Standby', tone: 'neutral', glyph: '◷' },
  OUT_OF_CREDITS: { label: 'Out of credits', tone: 'attention', glyph: '▲' },
  WRONG_NETWORK: { label: 'Wrong network', tone: 'failure', glyph: '✕' },
  NOT_CONFIGURED: { label: 'Not configured', tone: 'neutral', glyph: '–' },
};

/** An account state as a chip: in use is the only green one, out of credits needs attention, a wrong network fails. */
export function accountStateChip(state: string): AccountStateChip {
  const chip = STATE_CHIPS[state];
  return chip
    ? { ...chip, known: true }
    : { label: humanizeCode(state), tone: 'neutral', glyph: '–', known: false };
}

/** The accounts with the primary first, whatever order they arrived in; unknown names keep their order after them. */
export function orderedAccounts(accounts: RpcAccount[]): RpcAccount[] {
  const rank = (name: string) => {
    const at = ACCOUNT_ORDER.indexOf(name);
    return at < 0 ? ACCOUNT_ORDER.length : at;
  };
  return [...accounts].sort((a, b) => rank(a.name) - rank(b.name));
}

/**
 * Whether capture is held for credits: no account can take requests although at least one is configured. With no
 * account configured at all the RPC is simply not set up, which is not a hold.
 */
export function isCaptureHeld(data: RpcAccounts): boolean {
  return data.active == null && data.accounts.some((account) => account.configured);
}

/**
 * Whether an account has credits but is not used yet. With no active account that is a backup still waiting for
 * its one-time mainnet check: the backend offers it only after that check has passed.
 */
export function awaitsNetworkCheck(data: RpcAccounts): boolean {
  return data.active == null && data.accounts.some((account) => account.state === 'STANDBY');
}

/** Credits left as a share of the limit, 0 to 1; null without both figures or without a limit. */
export function creditsShare(
  remaining: number | null | undefined,
  limit: number | null | undefined,
): number | null {
  if (remaining == null || limit == null || !(limit > 0)) return null;
  return Math.max(0, Math.min(1, remaining / limit));
}

/** `remaining * 100 < limit * LOW_BALANCE_PERCENT`, the backend's own rule; an account without a limit is never low. */
export function isLowBalance(
  remaining: number | null | undefined,
  limit: number | null | undefined,
): boolean {
  if (remaining == null || limit == null || !(limit > 0)) return false;
  return remaining * 100 < limit * LOW_BALANCE_PERCENT;
}

/** "41.2% left", "100% left", or "under 0.1% left" for a share too small to show in one decimal. */
function shareLeftText(share: number): string {
  const percent = Number((share * 100).toFixed(1));
  return percent > 0 ? `${percent}% left` : 'under 0.1% left';
}

/**
 * The credits line under the figure: the share left, a low mark in words, and the credits used. An account with
 * nothing left says only what it used: the figure and the chip already say it is empty.
 */
export function creditsNote(
  account: Pick<RpcAccount, 'creditsRemaining' | 'creditsUsed' | 'creditsLimit'>,
): string {
  const { creditsRemaining: remaining, creditsUsed: used, creditsLimit: limit } = account;
  const parts: string[] = [];
  const share = creditsShare(remaining, limit);
  if (remaining != null && limit != null && limit > 0 && remaining > limit)
    parts.push('more than the plan limit: prepaid credits count too');
  else if (share != null && share > 0) parts.push(shareLeftText(share));
  if (remaining !== 0 && isLowBalance(remaining, limit))
    parts.push(`low: under ${LOW_BALANCE_PERCENT}%`);
  if (used != null) parts.push(`${formatCount(used)} used this cycle`);
  return parts.join(' · ');
}

/** Whole days from the UTC day of `now` to the UTC date `day`; null for a value that is not a real date. */
export function daysUntil(day: string, now: number): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  if (!match) return null;
  const end = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
  if (new Date(end).toISOString().slice(0, 10) !== day) return null;
  return Math.round((end - Math.floor(now / DAY_MS) * DAY_MS) / DAY_MS);
}

/**
 * When the billing cycle ends and the credits are renewed: the UTC date, and how far away it is in whole days
 * (null for a date that is not a real one). Null without a date.
 */
export function cycleEnd(
  day: string | null | undefined,
  now: number,
): { date: string; when: string | null } | null {
  if (!day) return null;
  const days = daysUntil(day, now);
  const date = `${formatDay(day)} (UTC)`;
  if (days == null) return { date, when: null };
  if (days === 0) return { date, when: 'today' };
  const count = `${Math.abs(days)} ${Math.abs(days) === 1 ? 'day' : 'days'}`;
  return { date, when: days > 0 ? `in ${count}` : `${count} ago` };
}

/** "30 Nov 2026 (UTC) · in 8 days", or a dash without a date. */
export function cycleEndText(day: string | null | undefined, now: number): string {
  const end = cycleEnd(day, now);
  if (!end) return '—';
  return end.when ? `${end.date} · ${end.when}` : end.date;
}
