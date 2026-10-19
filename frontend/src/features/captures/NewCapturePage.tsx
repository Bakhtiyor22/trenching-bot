import { generatedName } from './captureName';
import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Breadcrumbs,
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
  Tooltip,
  VisuallyHidden,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import {
  keepPreviousData,
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError } from '../../api/client';
import {
  capturesApi,
  type CaptureConfig,
  type CaptureCreate,
  WALLET_TOKEN_SCOPE,
} from '../../api/captures';
import { setupApi, type Recommendation } from '../../api/setup';
import { RelativeTime, ShortId } from '../../components/DisplayValues';
import { PageHeader, Section } from '../../components/Panels';
import { Term } from '../../components/PlainLanguage';
import { Recommendations } from '../../components/Recommendations';
import {
  EmptyState,
  ErrorState,
  LoadingState,
  ProviderErrorState,
} from '../../components/StateViews';
import { isSolanaAddress } from '../../lib/base58';
import { codeLabel } from '../../lib/codes';
import { paths } from '../../paths';
import { activityNote, solFromLamports } from './activityDisplay';
import { CommandError } from './CaptureShared';
import { useCaptureCommand, useCaptureConfig } from './captureQueries';

function focusMint() {
  document.getElementById('capture-mint')?.focus();
}

function WalletActivityPicker({
  wallet,
  label,
  mint,
  onSelect,
}: {
  wallet: string;
  label: string;
  mint: string;
  onSelect: (mint: string) => void;
}) {
  const queryClient = useQueryClient();
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
  const retryAt =
    activity.isError &&
    activity.error instanceof ApiError &&
    activity.error.code === 'PROVIDER_RATE_LIMITED'
      ? activity.errorUpdatedAt + Math.max(0, activity.error.retryAfterSeconds ?? 0) * 1000
      : 0;
  const [retryReadyAt, setRetryReadyAt] = useState(0);
  useEffect(() => {
    if (!retryAt) return;
    const timer = window.setTimeout(
      () => setRetryReadyAt(retryAt),
      Math.max(0, retryAt - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [retryAt]);
  const retryBlocked = retryReadyAt < retryAt;
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
  const newest = activity.data?.pages[0];
  function refreshActivity() {
    if (Date.now() < retryAt) return;
    refresh.current = true;
    void activity.refetch().finally(() => {
      refresh.current = false;
    });
  }
  function loadOlder() {
    if (Date.now() < retryAt) return;
    void activity.fetchNextPage();
  }
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Stack gap={3}>
          <Text size="sm">Pump.fun buys and sells (sells may be incomplete)</Text>
          {activity.data && (
            <Text size="xs" c="dimmed">
              Loaded <RelativeTime iso={new Date(activity.dataUpdatedAt).toISOString()} /> ·{' '}
              {activity.data.pages.reduce((sum, page) => sum + page.requestsUsed, 0)} requests
              {newest?.cachedAt ? ' (from cache)' : ''}
            </Text>
          )}
        </Stack>
        <Button
          variant="default"
          size="xs"
          loading={activity.isFetching && !activity.isFetchingNextPage}
          disabled={retryBlocked}
          onClick={refreshActivity}
        >
          Refresh activity
        </Button>
      </Group>
      <Group gap="xs">
        <Button
          size="xs"
          variant={buys ? 'filled' : 'default'}
          aria-pressed={buys}
          onClick={() => setBuys(!buys)}
        >
          Buys only
        </Button>
        <Button
          size="xs"
          variant={hideCaptured ? 'filled' : 'default'}
          aria-pressed={hideCaptured}
          onClick={() => setHideCaptured(!hideCaptured)}
        >
          Hide already captured
        </Button>
        <Tooltip label="Curve payment, excluding fees">
          <Button
            size="xs"
            variant={overThreshold ? 'filled' : 'default'}
            aria-pressed={overThreshold}
            onClick={() => setOverThreshold(!overThreshold)}
          >
            Over 1.9 SOL
          </Button>
        </Tooltip>
      </Group>
      {activity.isPending && activity.isFetching && (
        <Stack gap="xs">
          <LoadingState label={`Loading ${label}'s latest actions…`} />
          <Text size="xs" c="dimmed">
            at most 22 requests
          </Text>
          <Button
            variant="default"
            onClick={() =>
              void queryClient.cancelQueries({ queryKey: ['wallet-activity', wallet] })
            }
          >
            Cancel loading
          </Button>
        </Stack>
      )}
      {activity.isPending && !activity.isFetching && (
        <Text size="sm">
          Activity loading cancelled. Paste a token mint or refresh to try again.
        </Text>
      )}
      {!activity.isPending && !activity.isError && !rows.length && (
        <EmptyState
          title="No Pump.fun buys or sells found"
          description={
            activity.hasNextPage
              ? 'Older actions may still be available.'
              : 'Choose a token by pasting its mint below.'
          }
          action={
            activity.hasNextPage ? (
              <Button variant="default" onClick={loadOlder}>
                Look further back
              </Button>
            ) : (
              <Button variant="default" onClick={focusMint}>
                Paste a token mint
              </Button>
            )
          }
        />
      )}
      {!!rows.length && !visible.length && (
        <Text size="sm">
          No actions match these filters. Change the filters or paste a token mint.
        </Text>
      )}
      {!!visible.length && (
        <ScrollArea>
          <Table miw={760}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>
                  <VisuallyHidden>Select</VisuallyHidden>
                </Table.Th>
                {['When', 'Action', 'Token', 'SOL', 'Notes'].map((heading) => (
                  <Table.Th key={heading} ta={heading === 'SOL' ? 'right' : undefined}>
                    {heading}
                  </Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {visible.map((action) => {
                const key = `${action.signature}:${action.instructionIndex}:${action.innerIndex}`;
                const selected = mint === action.mint && selectedAction === key;
                const sol = solFromLamports(action.solLamports);
                const note = activityNote(action);
                return (
                  <Table.Tr key={key} bg={selected ? 'var(--mantine-color-blue-light)' : undefined}>
                    <Table.Td>
                      <Radio
                        name="activity-token"
                        aria-label={`Select ${action.mint} at slot ${action.slot}`}
                        checked={selected}
                        disabled={action.side === 'SELL'}
                        onChange={() => {
                          setSelectedAction(key);
                          onSelect(action.mint);
                        }}
                        title={
                          action.side === 'SELL'
                            ? 'Sells cannot start a capture'
                            : 'Select this token'
                        }
                      />
                    </Table.Td>
                    <Table.Td>
                      <RelativeTime iso={action.blockTime} />
                      <Text size="xs" c="dimmed">
                        {action.blockTime
                          ? new Date(action.blockTime).toLocaleTimeString('en-GB')
                          : 'Time unavailable'}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Badge variant="light" color={action.side === 'BUY' ? 'teal' : 'red'}>
                        {action.side === 'BUY' ? 'Buy' : 'Sell'}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Stack gap={2}>
                        <ShortId value={action.mint} />
                        <Anchor
                          href={`https://pump.fun/coin/${encodeURIComponent(action.mint)}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          size="xs"
                        >
                          pump.fun ↗
                        </Anchor>
                        <Text size="xs" c="dimmed">
                          Name not loaded
                        </Text>
                      </Stack>
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace" title={sol.full}>
                      {sol.display}
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs">
                        {note === 'captured'
                          ? action.capturedIn.map((capture, index) => (
                              <span key={capture.captureId}>
                                {index > 0 && ' · '}Captured in “
                                <Anchor
                                  component={Link}
                                  to={paths.capture(capture.captureId)}
                                  size="xs"
                                >
                                  {capture.displayName}
                                </Anchor>
                                ”
                              </span>
                            ))
                          : note}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      )}
      {activity.isError && (
        <ProviderErrorState
          key={activity.errorUpdatedAt}
          error={activity.error}
          retryAt={retryAt}
          onRetry={activity.isFetchNextPageError ? loadOlder : refreshActivity}
          alternatives={
            <Button variant="subtle" onClick={focusMint}>
              Paste a token mint instead
            </Button>
          }
        />
      )}
      {activity.data && (
        <Group justify="space-between">
          <Text size="xs" c="dimmed">
            {rows.length} most recent actions
          </Text>
          {activity.hasNextPage && (
            <Stack gap={2}>
              <Button
                variant="default"
                onClick={loadOlder}
                loading={activity.isFetchingNextPage}
                disabled={activity.isFetching || retryBlocked}
              >
                Load 20 older
              </Button>
              <Text size="xs" c="dimmed">
                up to 22 more requests
              </Text>
            </Stack>
          )}
        </Group>
      )}
      <Anchor component="button" type="button" size="xs" onClick={focusMint}>
        Token not listed? Paste a token mint
      </Anchor>
    </Stack>
  );
}

function NewCaptureForm({ config }: { config: CaptureConfig }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const suggested = config.suggestedWallets?.[0];
  const initialDefaults = suggested ? config.walletTokenDefaults : config.walletDefaults;
  const [mode, setMode] = useState<string | null>(
    params.get('mode') === 'attach' ? 'attach' : 'new',
  );
  const [wallet, setWallet] = useState(suggested?.address ?? '');
  const [name, setName] = useState('');
  const [scope, setScope] = useState(initialDefaults.scope);
  const [mint, setMint] = useState('');
  const [limit, setLimit] = useState(initialDefaults.limit);
  const [scanPages, setScanPages] = useState(initialDefaults.scanPages);
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
  const undo = useRef(
    new Map<string, () => void>(
      suggested
        ? [
            [
              'SCOPE_WALLET_TOKEN',
              () => {
                setScope(config.walletDefaults.scope);
                setLimit(config.walletDefaults.limit);
                setScanPages(config.walletDefaults.scanPages);
              },
            ],
          ]
        : [],
    ),
  );
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
  const walletLabel = [...(config.suggestedWallets ?? []), ...(config.recentWallets ?? [])].find(
    (item) => item.address === wallet.trim(),
  )?.label;
  const label = walletLabel ?? `${wallet.slice(0, 4)}…${wallet.slice(-4)}`;
  const preview = generatedName(
    mode === 'attach' ? 'Attached' : label,
    mode === 'attach'
      ? walletPath.trim().split(/[\\/]/).filter(Boolean).at(-1) || 'wallet folder'
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
                    <Alert color="violet" variant="light" title={`Suggested: ${suggested.label}`}>
                      <Stack gap="sm">
                        <Text size="sm">
                          The agreed strategy follows {suggested.label}'s buys. Start here, or paste
                          another wallet below.
                        </Text>
                        <Group>
                          {wallet === suggested.address && <Badge variant="light">Applied</Badge>}
                          <Button
                            variant="default"
                            disabled={locked}
                            onClick={() => {
                              setWallet(wallet === suggested.address ? '' : suggested.address);
                              setTokenOpen(null);
                            }}
                          >
                            {wallet === suggested.address ? 'Undo' : `Use ${suggested.label}`}
                          </Button>
                        </Group>
                      </Stack>
                    </Alert>
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
                    leftSection={
                      walletLabel ? (
                        <Badge size="xs" variant="light">
                          {walletLabel}
                        </Badge>
                      ) : undefined
                    }
                    leftSectionWidth={
                      walletLabel ? Math.max(65, walletLabel.length * 8 + 20) : undefined
                    }
                    required
                  />
                  {config.recentWallets?.some((recent) => recent.address !== wallet.trim()) && (
                    <Group gap="xs">
                      <Text size="xs">Other wallets you used:</Text>
                      {config.recentWallets
                        .filter((recent) => recent.address !== wallet.trim())
                        .map((recent, index) => (
                          <Anchor
                            key={recent.address}
                            component="button"
                            type="button"
                            size="xs"
                            disabled={locked}
                            onClick={() => {
                              setWallet(recent.address);
                              setTokenOpen(null);
                            }}
                          >
                            {index > 0 && '· '}
                            {recent.label ??
                              `${recent.address.slice(0, 4)}…${recent.address.slice(-4)}`}
                          </Anchor>
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
                            label={label}
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
          <Section title={mode === 'attach' ? 'Name' : '3 · Name'}>
            <Stack>
              <TextInput
                label="Session name"
                description="Made from wallet, token and time. Edit it if you like; names must be unique."
                placeholder={preview}
                value={name}
                maxLength={120}
                onChange={(event) => setName(event.currentTarget.value)}
                disabled={locked}
                error={
                  nameCheck.data && !nameCheck.data.available && debouncedName === name
                    ? 'Another capture already has this name.'
                    : undefined
                }
              />
              <Text size="xs" c="dimmed">
                {name.trim() || preview}
              </Text>
              {nameCheck.data?.available && debouncedName === name && name.trim() && (
                <Text size="sm" c="teal">
                  ✓ Name is free
                </Text>
              )}
              {suggestion && (
                <Button variant="light" onClick={() => setName(suggestion)} title={suggestion}>
                  Use suggestion
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
          <Section title="What happens next">
            <Stack>
              <Text size="sm">
                {mode === 'new'
                  ? `Wallet step: at most ${tokenScope ? config.walletTokenBudgetDefaults.requestBudget : config.budgetDefaults.requestBudget} requests. The market step's cost depends on how busy the token was; you'll see it before you start.`
                  : 'The saved capture folder is checked offline. No provider requests are sent.'}
              </Text>
              <Group>
                <Button component={Link} to={paths.captures} variant="subtle">
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant="default"
                  onClick={() => setStartAfterCreate(false)}
                  loading={create.isPending}
                  disabled={!!create.pendingCommand}
                >
                  {mode === 'attach' ? 'Attach and inspect' : 'Create only'}
                </Button>
                {mode === 'new' && (
                  <Button
                    type="submit"
                    variant="filled"
                    onClick={() => setStartAfterCreate(true)}
                    disabled={locked || !config.rpcConfigured}
                  >
                    Create and start wallet step
                  </Button>
                )}
              </Group>
              {mode === 'new' && !config.rpcConfigured && (
                <Text size="xs" c="yellow">
                  Solana RPC is not set up. Configure SOLANA_RPC_URL for the backend before starting
                  the wallet step.
                </Text>
              )}
            </Stack>
          </Section>
        </Stack>
      </form>
      <Stack>
        {mode !== 'attach' && (
          <Recommendations
            items={(recommendations.data ?? []).map((item) => ({
              ...item,
              facts: {
                ...item.facts,
                budget: tokenScope
                  ? config.walletTokenBudgetDefaults.requestBudget
                  : config.budgetDefaults.requestBudget,
              },
            }))}
            appliedIds={[
              ...(scope === WALLET_TOKEN_SCOPE ? ['SCOPE_WALLET_TOKEN'] : []),
              ...(cutoffMode === 'DECIDED' ? ['CUTOFF_DECIDED'] : []),
              ...(recovery ? ['ENABLE_RECOVERY'] : []),
            ]}
            onApply={apply}
            onUndo={(item) => {
              const restore = undo.current.get(item.id);
              if (restore) restore();
              else if (item.setting?.field === 'scope') changeScope(config.walletDefaults.scope);
              else if (item.setting?.field === 'cutoffMode') setCutoffMode('FULL');
              else if (item.setting?.field === 'recovery') setRecovery(false);
            }}
            onAdjust={(item) => document.getElementById(`capture-${item.setting?.field}`)?.focus()}
          />
        )}
        {mode !== 'attach' && recommendations.isError && (
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
      <Breadcrumbs>
        <Anchor component={Link} to={paths.captures}>
          Captures
        </Anchor>
        <Text size="sm">New capture</Text>
      </Breadcrumbs>
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
