import type { RpcAccount, RpcAccountName, RpcAccounts } from '../api/types';

/**
 * The states `GET /api/solana/accounts` is mocked in. In the browser, reload the Solana page with
 * `?mockState=rpc-<scenario>` (for example `?mockState=rpc-all-out`); `?mockState=rpc-accounts-error` fails the read.
 */
export const RPC_ACCOUNT_SCENARIOS = [
  'healthy',
  'low-balance',
  'backup-active',
  'all-out',
  'backup-unverified',
  'no-backup',
  'balance-problem',
  'wrong-network',
  'unconfigured',
] as const;
export type RpcAccountScenario = (typeof RPC_ACCOUNT_SCENARIOS)[number];

export const RPC_SCENARIO_PREFIX = 'rpc-';
/** The mock state that makes the accounts read fail, to see the section's error state. */
export const RPC_ACCOUNTS_ERROR_STATE = 'rpc-accounts-error';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** A free Helius plan's monthly credits. */
const FREE_LIMIT = 1_000_000;
const HOST = 'mainnet.helius-rpc.com';

const iso = (time: number) => new Date(time).toISOString();
const day = (time: number) => iso(time).slice(0, 10);

function unconfigured(name: RpcAccountName): RpcAccount {
  return {
    name,
    configured: false,
    state: 'NOT_CONFIGURED',
    host: null,
    creditsRemaining: null,
    creditsUsed: null,
    creditsLimit: null,
    cycleEndsOn: null,
    plan: null,
    balanceCheckedAt: null,
    balanceProblem: null,
    outOfCreditsSince: null,
  };
}

/** A configured account with `remaining` of the free limit left, its balance read four minutes before `now`. */
function account(
  name: RpcAccountName,
  state: RpcAccount['state'],
  remaining: number,
  now: number,
  overrides: Partial<RpcAccount> = {},
): RpcAccount {
  return {
    name,
    configured: true,
    state,
    host: HOST,
    creditsRemaining: remaining,
    creditsUsed: FREE_LIMIT - remaining,
    creditsLimit: FREE_LIMIT,
    cycleEndsOn: day(now + (name === 'primary' ? 8 : 21) * DAY),
    plan: 'free',
    balanceCheckedAt: iso(now - 4 * MINUTE),
    balanceProblem: null,
    outOfCreditsSince: null,
    ...overrides,
  };
}

/** The accounts answer of `scenario`, with every time relative to `now` so "4 min ago" reads right in the browser. */
export function rpcAccountsFixture(scenario: RpcAccountScenario, now = Date.now()): RpcAccounts {
  const primaryOut = account('primary', 'OUT_OF_CREDITS', 0, now, {
    outOfCreditsSince: iso(now - 3 * HOUR),
  });
  switch (scenario) {
    case 'healthy':
      return {
        active: 'primary',
        accounts: [
          account('primary', 'ACTIVE', 412_300, now),
          account('backup', 'STANDBY', FREE_LIMIT, now),
        ],
      };
    case 'low-balance':
      return {
        active: 'primary',
        accounts: [
          account('primary', 'ACTIVE', 62_480, now),
          account('backup', 'STANDBY', FREE_LIMIT, now),
        ],
      };
    case 'backup-active':
      return {
        active: 'backup',
        accounts: [primaryOut, account('backup', 'ACTIVE', 874_112, now)],
      };
    case 'all-out':
      return {
        active: null,
        accounts: [
          { ...primaryOut, outOfCreditsSince: iso(now - 2 * DAY) },
          account('backup', 'OUT_OF_CREDITS', 0, now, {
            outOfCreditsSince: iso(now - 40 * MINUTE),
          }),
        ],
      };
    case 'backup-unverified':
      return {
        active: null,
        accounts: [primaryOut, account('backup', 'STANDBY', 874_112, now)],
      };
    case 'no-backup':
      return {
        active: 'primary',
        accounts: [account('primary', 'ACTIVE', 412_300, now), unconfigured('backup')],
      };
    case 'balance-problem':
      return {
        active: 'primary',
        accounts: [
          // Never read: no project id, so there is no balance at all.
          account('primary', 'ACTIVE', 0, now, {
            creditsRemaining: null,
            creditsUsed: null,
            creditsLimit: null,
            cycleEndsOn: null,
            plan: null,
            balanceProblem: 'NO_PROJECT_ID',
          }),
          // Read before, then rate limited: the last balance is kept beside the problem.
          account('backup', 'STANDBY', 874_112, now, { balanceProblem: 'RATE_LIMITED' }),
        ],
      };
    case 'wrong-network':
      return {
        active: 'primary',
        accounts: [
          account('primary', 'ACTIVE', 412_300, now),
          account('backup', 'WRONG_NETWORK', FREE_LIMIT, now, { host: 'devnet.helius-rpc.com' }),
        ],
      };
    case 'unconfigured':
      return { active: null, accounts: [unconfigured('primary'), unconfigured('backup')] };
  }
}

/** The scenario a `?mockState=` value names, or null when it names none. */
export function rpcScenarioOf(state: string | null): RpcAccountScenario | null {
  if (!state?.startsWith(RPC_SCENARIO_PREFIX)) return null;
  const name = state.slice(RPC_SCENARIO_PREFIX.length);
  return (RPC_ACCOUNT_SCENARIOS as readonly string[]).includes(name)
    ? (name as RpcAccountScenario)
    : null;
}
