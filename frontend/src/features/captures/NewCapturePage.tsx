import { generatedName } from './captureName';
import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Button,
  Checkbox,
  Group,
  NumberInput,
  Radio,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '../../api/client';
import {
  capturesApi,
  type CaptureConfig,
  type CaptureCreate,
  WALLET_TOKEN_SCOPE,
} from '../../api/captures';
import { setupApi, type Recommendation } from '../../api/setup';
import { ShortId, Timestamp } from '../../components/DisplayValues';
import { PageHeader, Section } from '../../components/Panels';
import { CodeText, Term } from '../../components/PlainLanguage';
import { Recommendations } from '../../components/Recommendations';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { isSolanaAddress } from '../../lib/base58';
import { codeLabel } from '../../lib/codes';
import { paths } from '../../paths';
import { CommandError } from './CaptureShared';
import { useCaptureCommand, useCaptureConfig } from './captureQueries';

function WalletActivityPicker({
  wallet,
  mint,
  onSelect,
}: {
  wallet: string;
  mint: string;
  onSelect: (mint: string) => void;
}) {
  const refresh = useRef(false);
  const [buys, setBuys] = useState(true);
  const [hideCaptured, setHideCaptured] = useState(false);
  const [overThreshold, setOverThreshold] = useState(false);
  const [selectedAction, setSelectedAction] = useState<string | null>(null);
  const activity = useInfiniteQuery({
    queryKey: ['wallet-activity', wallet],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      setupApi.activity(wallet, pageParam, refresh.current, signal),
    getNextPageParam: (page) => page.nextBefore ?? undefined,
    retry: false,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });
  const rows = [
    ...new Map(
      (activity.data?.pages.flatMap((page) => page.actions) ?? []).map((action) => [
        `${action.signature}:${action.instructionIndex}:${action.innerIndex}`,
        action,
      ]),
    ).values(),
  ];
  const visible = rows.filter(
    (action) =>
      (!buys || action.side === 'BUY') &&
      (!hideCaptured || !action.capturedIn.length) &&
      (!overThreshold || action.overSignalThreshold),
  );
  const retryAt =
    activity.error instanceof ApiError && activity.error.retryAfterSeconds
      ? activity.errorUpdatedAt + activity.error.retryAfterSeconds * 1000
      : null;
  const [now, setNow] = useState(Date.now);
  // The countdown is only mounted after a rate-limit error, avoiding polling the provider.
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm">Pump.fun buys and sells (sells may be incomplete)</Text>
        <Button
          variant="default"
          size="xs"
          loading={activity.isFetching && !activity.isFetchingNextPage}
          disabled={retryAt !== null && now < retryAt}
          onClick={() => {
            refresh.current = true;
            void activity.refetch().finally(() => {
              refresh.current = false;
            });
          }}
        >
          Refresh activity
        </Button>
      </Group>
      {retryAt && <RetryCountdown until={retryAt} onTick={setNow} />}
      <Group gap="md">
        <Checkbox
          label="Buys only"
          checked={buys}
          onChange={(event) => setBuys(event.currentTarget.checked)}
        />
        <Checkbox
          label="Hide already captured"
          checked={hideCaptured}
          onChange={(event) => setHideCaptured(event.currentTarget.checked)}
        />
        <Checkbox
          label="Over 1.9 SOL"
          description="Curve payment, excluding fees"
          checked={overThreshold}
          onChange={(event) => setOverThreshold(event.currentTarget.checked)}
        />
      </Group>
      {activity.isPending && <LoadingState label="Loading recent wallet activity…" />}
      {activity.isError && (
        <Alert color="yellow" title="Wallet activity unavailable">
          {codeLabel(
            activity.error instanceof ApiError ? activity.error.code : 'PROVIDER_UNAVAILABLE',
          )}
          <CodeText
            code={activity.error instanceof ApiError ? activity.error.code : 'PROVIDER_UNAVAILABLE'}
          />
        </Alert>
      )}
      {activity.data && (
        <Text size="xs" c="dimmed">
          {activity.data.pages.reduce((sum, page) => sum + page.requestsUsed, 0)} requests · loaded{' '}
          <Timestamp iso={new Date(activity.dataUpdatedAt).toISOString()} /> ·{' '}
          {activity.data.pages.reduce((sum, page) => sum + page.unreadable, 0)} unreadable
          transactions
        </Text>
      )}
      {!activity.isPending && visible.length === 0 && (
        <Text size="sm">
          No trades match these filters. Paste a token mint below or load older activity.
        </Text>
      )}
      {!!visible.length && (
        <ScrollArea>
          <Table miw={650}>
            <Table.Thead>
              <Table.Tr>
                {['Choose', 'Token', 'Trade', 'When', 'Notes'].map((label) => (
                  <Table.Th key={label}>{label}</Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {visible.map((action) => (
                <Table.Tr
                  key={`${action.signature}:${action.instructionIndex}:${action.innerIndex}`}
                >
                  <Table.Td>
                    <Radio
                      name="activity-token"
                      aria-label={`Select ${action.mint} at slot ${action.slot}`}
                      checked={
                        mint === action.mint &&
                        selectedAction ===
                          `${action.signature}:${action.instructionIndex}:${action.innerIndex}`
                      }
                      disabled={action.side === 'SELL'}
                      onChange={() => {
                        setSelectedAction(
                          `${action.signature}:${action.instructionIndex}:${action.innerIndex}`,
                        );
                        onSelect(action.mint);
                      }}
                      title={
                        action.side === 'SELL'
                          ? 'Choose a buy to select a reference token.'
                          : 'Select this token'
                      }
                    />
                  </Table.Td>
                  <Table.Td>
                    <Anchor
                      href={`https://pump.fun/coin/${encodeURIComponent(action.mint)}`}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      <ShortId value={action.mint} />
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={action.side === 'BUY' ? 'teal' : 'red'}>
                      {action.side}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Timestamp iso={action.blockTime} />
                    <Text size="xs">Slot {action.slot}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs">
                      {action.earlierActionInPage
                        ? 'An older action is in this page. '
                        : 'No first-buy claim. '}
                      {action.side === 'SELL' && 'Sells cannot be selected. '}
                    </Text>
                    {action.capturedIn.map((capture) => (
                      <Anchor
                        key={capture.captureId}
                        component={Link}
                        to={paths.capture(capture.captureId)}
                        size="xs"
                      >
                        Already captured: {capture.displayName}
                      </Anchor>
                    ))}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {activity.hasNextPage && (
        <Button
          variant="default"
          onClick={() => void activity.fetchNextPage()}
          loading={activity.isFetchingNextPage}
          disabled={activity.isFetching || (retryAt !== null && now < retryAt)}
        >
          Load 20 older
        </Button>
      )}
    </Stack>
  );
}

function RetryCountdown({ until, onTick }: { until: number; onTick: (now: number) => void }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => {
      const next = Date.now();
      setNow(next);
      onTick(next);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [onTick]);
  return (
    <Text size="sm" role="status">
      {now < until
        ? `Retry available in ${Math.ceil((until - now) / 1000)} seconds.`
        : 'You can refresh again.'}
    </Text>
  );
}

function NewCaptureForm({ config }: { config: CaptureConfig }) {
  const navigate = useNavigate();
  const suggested = config.suggestedWallets?.[0];
  const [mode, setMode] = useState<string | null>('new');
  const [wallet, setWallet] = useState(suggested?.address ?? '');
  const [name, setName] = useState('');
  const [scope, setScope] = useState(config.walletDefaults.scope);
  const [mint, setMint] = useState('');
  const [limit, setLimit] = useState(config.walletDefaults.limit);
  const [scanPages, setScanPages] = useState(config.walletDefaults.scanPages);
  const [cutoffMode, setCutoffMode] = useState<'FULL' | 'DECIDED'>('FULL');
  const [recovery, setRecovery] = useState(false);
  const [walletPath, setWalletPath] = useState('');
  const [marketPath, setMarketPath] = useState('');
  const [ratesPath, setRatesPath] = useState('');
  const [validation, setValidation] = useState('');
  const [tokenOpen, setTokenOpen] = useState<string | null>(null);
  const [startAfterCreate, setStartAfterCreate] = useState(false);
  const [at] = useState(() => new Date());
  const walletValid = isSolanaAddress(wallet.trim());
  const tokenScope = scope === WALLET_TOKEN_SCOPE;
  function changeScope(next: string) {
    const defaults =
      next === WALLET_TOKEN_SCOPE ? config.walletTokenDefaults : config.walletDefaults;
    setScope(next);
    setLimit(defaults.limit);
    setScanPages(defaults.scanPages);
  }
  const [debouncedName] = useDebouncedValue(name, 300);
  const nameCheck = useQuery({
    queryKey: ['capture-name', debouncedName],
    queryFn: ({ signal }) => capturesApi.checkName(debouncedName, signal),
    enabled: !!debouncedName.trim(),
    retry: false,
  });
  const recommendations = useQuery({
    placeholderData: keepPreviousData,
    queryKey: ['capture-recommendations', wallet, scope, mint],
    queryFn: ({ signal }) =>
      setupApi.captureRecommendations(
        walletValid ? wallet.trim() : '',
        scope,
        isSolanaAddress(mint) ? mint : '',
        signal,
      ),
    staleTime: 60_000,
  });
  const undo = useRef(new Map<string, () => void>());
  function apply(item: Recommendation) {
    if (item.setting?.field === 'scope') {
      const previous = { scope, limit, scanPages };
      undo.current.set(item.id, () => {
        setScope(previous.scope);
        setLimit(previous.limit);
        setScanPages(previous.scanPages);
      });
      changeScope(item.setting.value);
    }
    if (item.setting?.field === 'cutoffMode') {
      undo.current.set(item.id, () => setCutoffMode(cutoffMode));
      setCutoffMode(item.setting.value as 'FULL' | 'DECIDED');
    }
    if (item.setting?.field === 'recovery') {
      undo.current.set(item.id, () => setRecovery(recovery));
      setRecovery(true);
    }
  }
  const create = useCaptureCommand<CaptureCreate, Awaited<ReturnType<typeof capturesApi.create>>>(
    'create',
    capturesApi.create,
    (result) => {
      void navigate(paths.capture(result.id), {
        state: { startWallet: startAfterCreate, recovery },
      });
    },
  );
  const locked = create.isPending || !!create.pendingCommand;
  const suggestion =
    create.error instanceof ApiError ? create.error.suggestion : nameCheck.data?.suggestion;
  function submit(event: FormEvent) {
    event.preventDefault();
    if (
      mode === 'new' &&
      (!walletValid ||
        !Number.isInteger(limit) ||
        limit < 1 ||
        limit > config.bounds.walletLimitMax ||
        !Number.isInteger(scanPages) ||
        scanPages < 1 ||
        scanPages > config.bounds.walletPagesMax)
    ) {
      setValidation('Enter a valid wallet address and wallet settings within the limits.');
      return;
    }
    if (mode === 'new' && tokenScope && !isSolanaAddress(mint.trim())) {
      setValidation('Enter a valid token mint address.');
      return;
    }
    if (mode === 'attach' && !walletPath.trim()) {
      setValidation('A wallet capture directory is required.');
      return;
    }
    setValidation('');
    create.submit({
      name: name.trim(),
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      ...(mode === 'new'
        ? {
            cutoffMode,
            wallet: {
              wallet: wallet.trim(),
              scope,
              limit,
              scanPages,
              ...(tokenScope ? { mint: mint.trim() } : {}),
            },
          }
        : {
            attach: {
              wallet: walletPath.trim(),
              ...(marketPath.trim() ? { market: marketPath.trim() } : {}),
              ...(ratesPath.trim() ? { rates: ratesPath.trim() } : {}),
            },
          }),
    });
  }
  const label =
    config.suggestedWallets?.find((item) => item.address === wallet)?.label ??
    `${wallet.slice(0, 4)}…${wallet.slice(-4)}`;
  const preview = generatedName(
    mode === 'attach' ? 'Attached' : label,
    mode === 'attach'
      ? (walletPath.split(/[\\/]/).at(-1) ?? 'evidence')
      : tokenScope
        ? mint
        : scope === 'wallet-references'
          ? 'Wallet references'
          : 'Pump.fun buys',
    at,
    mode !== 'attach' && tokenScope,
  );
  return (
    <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="lg">
      <form onSubmit={submit} aria-label="New capture">
        <Stack gap="lg">
          <Tabs value={mode} onChange={setMode}>
            <Tabs.List>
              <Tabs.Tab value="new" disabled={locked}>
                New capture
              </Tabs.Tab>
              <Tabs.Tab value="attach" disabled={locked}>
                Attach existing
              </Tabs.Tab>
            </Tabs.List>
          </Tabs>
          {mode === 'new' ? (
            <>
              <Section title="1 · Whose trades?">
                <Stack>
                  {suggested && (
                    <Group justify="space-between">
                      <Text size="sm">{suggested.label} · suggested by the agreed strategy</Text>
                      <Button
                        variant="light"
                        disabled={locked}
                        onClick={() =>
                          setWallet(wallet === suggested.address ? '' : suggested.address)
                        }
                      >
                        {wallet === suggested.address ? 'Applied · Undo' : `Use ${suggested.label}`}
                      </Button>
                    </Group>
                  )}
                  <TextInput
                    id="capture-wallet"
                    label="Wallet address"
                    value={wallet}
                    onChange={(event) => {
                      setWallet(event.currentTarget.value);
                      setTokenOpen(null);
                    }}
                    disabled={locked}
                    autoComplete="off"
                    spellCheck={false}
                    required
                  />
                  {!!config.recentWallets?.length && (
                    <Group gap="xs">
                      <Text size="xs">Recent wallets:</Text>
                      {config.recentWallets.map((recent) => (
                        <Button
                          key={recent.address}
                          size="xs"
                          variant="subtle"
                          onClick={() => {
                            setWallet(recent.address);
                            setTokenOpen(null);
                          }}
                        >
                          {recent.label ??
                            `${recent.address.slice(0, 4)}…${recent.address.slice(-4)}`}
                        </Button>
                      ))}
                    </Group>
                  )}
                </Stack>
              </Section>
              <Section title="2 · Which token?">
                <Stack>
                  <Accordion value={tokenOpen} onChange={setTokenOpen}>
                    <Accordion.Item value="activity">
                      <Accordion.Control disabled={!walletValid}>
                        Choose from recent wallet activity
                      </Accordion.Control>
                      <Accordion.Panel>
                        {tokenOpen && walletValid && (
                          <WalletActivityPicker
                            key={wallet}
                            wallet={wallet.trim()}
                            mint={mint}
                            onSelect={(value) => {
                              setMint(value);
                              changeScope(WALLET_TOKEN_SCOPE);
                            }}
                          />
                        )}
                      </Accordion.Panel>
                    </Accordion.Item>
                  </Accordion>
                  <TextInput
                    id="capture-mint"
                    label="Token mint address"
                    description="Paste a token mint as an alternative to loading activity."
                    value={mint}
                    onChange={(event) => {
                      setMint(event.currentTarget.value);
                      if (scope !== WALLET_TOKEN_SCOPE) changeScope(WALLET_TOKEN_SCOPE);
                    }}
                    disabled={locked}
                    autoComplete="off"
                    spellCheck={false}
                  />
                  <Select
                    id="capture-scope"
                    label="Discovery scope"
                    value={scope}
                    onChange={(value) => changeScope(value ?? config.walletDefaults.scope)}
                    allowDeselect={false}
                    disabled={locked}
                    data={[
                      { value: 'pumpfun-user-buys', label: 'Pump.fun user buys' },
                      { value: 'wallet-references', label: 'Wallet references' },
                      { value: WALLET_TOKEN_SCOPE, label: 'Wallet + token' },
                    ]}
                  />
                  <Text size="xs" c="dimmed">
                    Selecting a buy chooses the token. First-buy verification happens during
                    capture; whether it qualifies is only known after market capture.
                  </Text>
                  <Text size="xs" c="dimmed">
                    Effective settings: {limit} transaction(s), {scanPages} scan pages.
                  </Text>
                </Stack>
              </Section>
            </>
          ) : (
            <Section title="Attach existing evidence">
              <Stack>
                <Text size="sm">
                  Paths are relative to the backend capture root. Attachment verifies evidence
                  offline.
                </Text>
                <TextInput
                  label="Wallet capture directory"
                  value={walletPath}
                  onChange={(event) => setWalletPath(event.currentTarget.value)}
                  disabled={locked}
                  required
                />
                <TextInput
                  label="Market capture directory (optional)"
                  value={marketPath}
                  onChange={(event) => setMarketPath(event.currentTarget.value)}
                  disabled={locked}
                />
                <TextInput
                  label="Rates capture directory (optional)"
                  value={ratesPath}
                  onChange={(event) => setRatesPath(event.currentTarget.value)}
                  disabled={locked}
                />
              </Stack>
            </Section>
          )}
          <Section title="3 · Name">
            <Stack>
              <TextInput
                label="Session name"
                description="Leave blank to generate a readable name."
                placeholder={preview}
                value={name}
                maxLength={120}
                onChange={(event) => setName(event.currentTarget.value)}
                disabled={locked}
                error={
                  nameCheck.data && !nameCheck.data.available && debouncedName === name
                    ? 'This name is taken.'
                    : undefined
                }
              />
              <Text size="xs" c="dimmed">
                {name.trim() || preview}
              </Text>
              {suggestion && (
                <Button variant="light" onClick={() => setName(suggestion)}>
                  Use suggestion: {suggestion}
                </Button>
              )}
              {nameCheck.isError && (
                <Text size="xs" c="yellow">
                  Name availability could not be checked. Creation will check it again.
                </Text>
              )}
            </Stack>
          </Section>
          {mode === 'new' && (
            <Accordion variant="separated">
              <Accordion.Item value="advanced">
                <Accordion.Control>Advanced limits and recovery</Accordion.Control>
                <Accordion.Panel>
                  <Stack>
                    <NumberInput
                      label="Sample transactions"
                      min={1}
                      max={config.bounds.walletLimitMax}
                      value={limit}
                      allowDecimal={false}
                      onChange={(value) => setLimit(Number(value))}
                    />
                    <NumberInput
                      label="Wallet scan pages"
                      min={1}
                      max={config.bounds.walletPagesMax}
                      value={scanPages}
                      allowDecimal={false}
                      onChange={(value) => setScanPages(Number(value))}
                    />
                    <Select
                      id="capture-cutoffMode"
                      label="Market cutoff"
                      value={cutoffMode}
                      allowDeselect={false}
                      onChange={(value) => setCutoffMode(value as 'FULL' | 'DECIDED')}
                      data={[
                        { value: 'FULL', label: 'Record the whole window' },
                        { value: 'DECIDED', label: 'Stop at a decided legacy outcome' },
                      ]}
                    />
                    {cutoffMode === 'DECIDED' && (
                      <Alert color="yellow">{codeLabel('CUTOFF_USES_LEGACY_DEFAULTS')}</Alert>
                    )}
                    <Checkbox
                      id="capture-recovery"
                      label="Enable automatic recovery when starting the wallet step"
                      checked={recovery}
                      onChange={(event) => setRecovery(event.currentTarget.checked)}
                    />
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            </Accordion>
          )}
          {validation && (
            <Alert color="red" role="alert">
              {validation}
            </Alert>
          )}
          <CommandError
            error={create.error}
            uncertain={!!create.pendingCommand && !create.isPending}
            retry={create.retry}
          />
          <Group>
            <Button
              type="submit"
              onClick={() => setStartAfterCreate(false)}
              loading={create.isPending}
              disabled={!!create.pendingCommand}
            >
              {mode === 'attach' ? 'Attach and inspect' : 'Create capture'}
            </Button>
            {mode === 'new' && (
              <Button
                type="submit"
                variant="light"
                onClick={() => setStartAfterCreate(true)}
                disabled={locked || !config.rpcConfigured}
              >
                Create and start wallet step
              </Button>
            )}
          </Group>
          <Text size="xs" c="dimmed">
            {tokenScope
              ? config.walletTokenBudgetDefaults.requestBudget
              : config.budgetDefaults.requestBudget}{' '}
            requests per wallet invocation. <Term>Recovery</Term> uses at most three such
            allowances.
          </Text>
        </Stack>
      </form>
      <Stack>
        <Recommendations
          items={recommendations.data ?? []}
          onApply={apply}
          onUndo={(item) => undo.current.get(item.id)?.()}
          onAdjust={(item) => document.getElementById(`capture-${item.setting?.field}`)?.focus()}
        />
        {recommendations.isError && (
          <ErrorState
            error={recommendations.error}
            title="Suggestions unavailable"
            onRetry={() => void recommendations.refetch()}
          />
        )}
        <Section title="Before you start">
          <Text size="sm">
            A <Term>Dataset</Term> is recorded evidence. A <Term>Run</Term> replays it as a{' '}
            <Term>Paper trade</Term>. Captures can stop at a limit and resume from saved progress.
          </Text>
        </Section>
      </Stack>
    </SimpleGrid>
  );
}
export function NewCapturePage() {
  const config = useCaptureConfig();
  return (
    <Stack gap="lg">
      <PageHeader
        title="New capture"
        description="Choose a wallet and token, then collect the evidence for a replay."
        actions={
          <Button component={Link} variant="default" to={paths.captures}>
            All captures
          </Button>
        }
      />
      {config.isPending ? (
        <LoadingState label="Loading capture settings…" />
      ) : config.isError ? (
        <ErrorState error={config.error} onRetry={() => void config.refetch()} />
      ) : (
        <NewCaptureForm config={config.data} />
      )}
    </Stack>
  );
}
