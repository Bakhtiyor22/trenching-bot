import { Alert, Card, Code, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { useEffect, useState, type ReactNode } from 'react';
import { useSolanaAccounts } from '../../api/queries';
import type { RpcAccount, RpcAccounts, RpcBalanceProblem } from '../../api/types';
import { RelativeTime, Timestamp } from '../../components/DisplayValues';
import { IconAlert, IconInfo } from '../../components/Icons';
import { Meter } from '../../components/Meter';
import { Flash, KeyValueTable, Section } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { QueryView } from '../../components/QueryView';
import { StatusChip } from '../../components/StatusChip';
import { formatCount, humanizeCode } from '../../lib/format';
import { LiveCue } from '../captures/CaptureShared';
import { CreditsHoldAlert } from './RpcCreditsHold';
import {
  accountLabel,
  accountStateChip,
  creditsNote,
  cycleEnd,
  isLowBalance,
  orderedAccounts,
  projectIdVariable,
  rpcUrlVariable,
} from './rpcAccounts';

const MINUTE_MS = 60_000;
const NEXT_CHECK = 'It is tried again at the next balance check; there is nothing to do.';
const FAILOVER_STILL_WORKS =
  'The bot still moves to the other account when this one runs out; it only cannot show these credits or warn before they run low.';

/** The current time, re-read every minute, so "in 8 days" does not go stale on a page left open. */
function useNow(): number {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), MINUTE_MS);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

/** What a balance problem means and what to do about it; variable names only, never a value. */
function balanceProblemHelp(problem: RpcBalanceProblem | string, account: RpcAccount): ReactNode {
  const url = <Code>{rpcUrlVariable(account.name)}</Code>;
  const projectId = <Code>{projectIdVariable(account.name)}</Code>;
  switch (problem) {
    case 'NO_PROJECT_ID':
      return (
        <>
          No Helius project id is set for this account, so its credits cannot be read. Set{' '}
          {projectId} for the backend process on the server and restart it; the id is shown in the
          Helius dashboard. {FAILOVER_STILL_WORKS}
        </>
      );
    case 'NO_API_KEY':
      return (
        <>
          This account&apos;s RPC URL carries no <Code>api-key</Code> parameter, and the balance is
          read with that key. Check {url} on the server: a Helius URL ends in{' '}
          <Code>?api-key=…</Code>.
        </>
      );
    case 'INVALID_PROJECT':
      return (
        <>
          Helius says the project id does not belong to this account&apos;s key. Check {projectId}{' '}
          on the server: it must be the project of the same Helius account as {url}.
        </>
      );
    case 'AUTHENTICATION':
      return (
        <>
          Helius refused this account&apos;s key for the balance read. Check that the key in {url}{' '}
          on the server is still valid; it may have been replaced or deleted in the Helius
          dashboard.
        </>
      );
    case 'ADMIN_API_DISABLED':
      return (
        <>
          Helius has not enabled its admin API for this project, so the credits cannot be read here.
          Read them in the Helius dashboard instead. {FAILOVER_STILL_WORKS}
        </>
      );
    case 'RATE_LIMITED':
      return <>Helius rate limited the balance read. {NEXT_CHECK}</>;
    case 'UNAVAILABLE':
      return <>Helius could not be reached for the balance read. {NEXT_CHECK}</>;
    case 'MALFORMED':
      return (
        <>
          Helius answered the balance read in a form this version cannot read. If it stays this way
          the provider may have changed its API: read the credits in the Helius dashboard meanwhile.
        </>
      );
    default:
      return <>The balance could not be read: {humanizeCode(problem).toLowerCase()}.</>;
  }
}

function BalanceProblem({ account }: { account: RpcAccount }) {
  if (!account.balanceProblem) return null;
  return (
    <Alert color="orange" variant="light" p="xs" title="The balance could not be read">
      <Stack gap={4}>
        <Text size="sm">{balanceProblemHelp(account.balanceProblem, account)}</Text>
        <Text size="sm" c="dimmed">
          {account.creditsRemaining == null
            ? 'No balance has been read for this account yet.'
            : 'The credits shown are from the last read that worked.'}
        </Text>
        <CodeText code={account.balanceProblem} />
      </Stack>
    </Alert>
  );
}

/** What the account's state means for capture, in a sentence; nothing for the account in use. */
function StateNote({ account, data }: { account: RpcAccount; data: RpcAccounts }) {
  switch (account.state) {
    case 'STANDBY':
      return (
        <Text size="sm" c="dimmed">
          {data.active == null
            ? 'Has credits, but is not used until it has been checked as Solana mainnet.'
            : 'Has credits and takes over when the account in use runs out.'}
        </Text>
      );
    case 'OUT_OF_CREDITS':
      return (
        <Text size="sm" c="dimmed">
          {account.balanceProblem
            ? 'Its balance cannot be read, so it is asked once an hour and is used again as soon as it answers.'
            : 'It is used again on its own once a balance read shows credits, normally after the cycle ends.'}
        </Text>
      );
    case 'WRONG_NETWORK':
      return (
        <Alert
          color="red"
          variant="light"
          p="xs"
          title="Not on Solana mainnet"
          icon={<IconAlert />}
        >
          <Text size="sm">
            This account answers for another network, so it is never used. Point{' '}
            <Code>{rpcUrlVariable(account.name)}</Code> at a Solana mainnet endpoint on the server
            and restart the backend.
          </Text>
        </Alert>
      );
    default:
      return null;
  }
}

function Credits({ account, label }: { account: RpcAccount; label: string }) {
  const { creditsRemaining: remaining, creditsLimit: limit } = account;
  if (remaining == null) {
    return (
      <Stack gap={4}>
        <span className="console-label">Credits left</span>
        <div className="console-kpi-value" data-text>
          <Text span c="dimmed" inherit>
            Not known
          </Text>
        </div>
      </Stack>
    );
  }
  const low = isLowBalance(remaining, limit);
  const note = creditsNote(account);
  return (
    <Stack gap={6}>
      <span className="console-label">Credits left</span>
      <div className="console-kpi-value">
        <Flash watch={remaining}>{formatCount(remaining)}</Flash>
        {limit != null && <span className="console-unit">/ {formatCount(limit)}</span>}
      </div>
      {limit != null && limit > 0 && (
        <Meter
          label={`${label} account: credits left of the limit`}
          value={remaining}
          max={limit}
          color={low ? 'var(--console-rejected)' : 'var(--console-info)'}
        />
      )}
      {note && (
        <Text size="xs" c="dimmed">
          {note}
        </Text>
      )}
    </Stack>
  );
}

/** The cycle's end date with the days left beside it, so a narrow cell wraps between the two before it breaks either. */
function CycleEnd({ day, now }: { day: string | null; now: number }) {
  const end = cycleEnd(day, now);
  if (!end) return <>—</>;
  return (
    <Group gap={6} wrap="wrap">
      <Text span size="sm">
        {end.date}
      </Text>
      {end.when && (
        <Text span size="xs" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
          {end.when}
        </Text>
      )}
    </Group>
  );
}

/** How to add the account, by the name of its variable; the URL itself is never typed into the console. */
function NotConfigured({ account }: { account: RpcAccount }) {
  const backup = account.name === 'backup';
  return (
    <Alert color="gray" variant="light" p="xs" icon={<IconInfo />}>
      <Text size="sm">
        {backup
          ? 'No backup account is set up. A second Helius account lets capture go on while the primary is out of credits. To add one, set '
          : 'This account is not set up. Set '}
        <Code>{rpcUrlVariable(account.name)}</Code> for the backend process on the server
        {backup && (
          <>
            {' '}
            (and <Code>{projectIdVariable(account.name)}</Code> to see its credits here)
          </>
        )}
        , then restart it. The URL is a secret: the console never asks for it and only ever shows
        the host.
      </Text>
    </Alert>
  );
}

function AccountCard({
  account,
  data,
  now,
}: {
  account: RpcAccount;
  data: RpcAccounts;
  now: number;
}) {
  const label = accountLabel(account.name);
  const chip = accountStateChip(account.state);
  const configured = account.state !== 'NOT_CONFIGURED';
  return (
    <Card withBorder padding="md" component="article" aria-label={`${label} RPC account`}>
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start" gap="xs" wrap="wrap">
          <Stack gap={0} style={{ minWidth: 0 }}>
            <Text fw={600}>{label}</Text>
            <Text size="xs" c="dimmed" ff="monospace" style={{ overflowWrap: 'anywhere' }}>
              {account.host ?? 'no endpoint'}
            </Text>
          </Stack>
          <Stack gap={2} align="flex-end">
            <StatusChip tone={chip.tone} glyph={chip.glyph}>
              {chip.label}
            </StatusChip>
            {!chip.known && <CodeText code={account.state} />}
          </Stack>
        </Group>
        {configured ? (
          <>
            <Credits account={account} label={label} />
            <KeyValueTable
              rows={[
                ['Cycle ends', <CycleEnd key="cycle" day={account.cycleEndsOn} now={now} />],
                ['Plan', account.plan ?? '—'],
                [
                  'Balance checked',
                  account.balanceCheckedAt ? (
                    <Group key="checked" gap={6} wrap="wrap">
                      <Timestamp iso={account.balanceCheckedAt} />
                      <RelativeTime iso={account.balanceCheckedAt} size="xs" c="dimmed" />
                    </Group>
                  ) : (
                    'Not read yet'
                  ),
                ],
                ...(account.outOfCreditsSince
                  ? [
                      [
                        'Out of credits since',
                        <Timestamp key="out" iso={account.outOfCreditsSince} />,
                      ] as [ReactNode, ReactNode],
                    ]
                  : []),
              ]}
            />
            <StateNote account={account} data={data} />
            <BalanceProblem account={account} />
          </>
        ) : (
          <NotConfigured account={account} />
        )}
      </Stack>
    </Card>
  );
}

/** Which account takes the requests now, in a sentence above the two cards. */
function InUse({ data }: { data: RpcAccounts }) {
  if (data.active == null) return null;
  return (
    <Text size="sm">
      {data.active === 'primary'
        ? 'Requests go to the primary account.'
        : `Requests go to the ${data.active} account while the primary is out of credits; the primary takes over again as soon as it has credits.`}
    </Text>
  );
}

function Accounts({ data }: { data: RpcAccounts }) {
  const now = useNow();
  const accounts = orderedAccounts(data.accounts);
  if (!accounts.some((account) => account.configured)) {
    return (
      <Text size="sm" c="dimmed">
        No RPC account is set up yet. The accounts and their credits appear here once a Solana RPC
        is configured (see RPC status below); a second account can then be added as a backup.
      </Text>
    );
  }
  return (
    <Stack gap="md">
      <CreditsHoldAlert data={data} />
      <InUse data={data} />
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
        {accounts.map((account) => (
          <AccountCard key={account.name} account={account} data={data} now={now} />
        ))}
      </SimpleGrid>
    </Stack>
  );
}

/**
 * The bot's two RPC accounts: which one takes the requests, each one's state, credits left of the limit, cycle end and
 * last balance read. Read from the backend's memory and polled with the rest of the page; it never shows a URL, key or
 * project id, and never asks for one.
 */
export function RpcAccountsSection() {
  const accounts = useSolanaAccounts();
  return (
    <Section
      title="RPC accounts"
      hint="The bot uses the primary account and moves to the backup while the primary is out of credits. Credits are read from the provider about every 15 minutes."
      actions={<LiveCue active={accounts.data !== undefined && !accounts.isError} />}
    >
      <QueryView
        query={accounts}
        loadingLabel="Loading RPC accounts…"
        errorTitle="Could not load the RPC accounts"
      >
        {(data) => <Accounts data={data} />}
      </QueryView>
    </Section>
  );
}
