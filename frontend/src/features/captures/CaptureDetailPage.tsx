import {
  Accordion,
  Alert,
  Anchor,
  Button,
  Checkbox,
  Group,
  NumberInput,
  ScrollArea,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import {
  capturesApi,
  type CaptureAttemptRequest,
  type CaptureBudget,
  type CaptureConfig,
  type CaptureOperation,
  type CaptureOperationControlRequest,
  type CaptureOperationRequest,
  type CaptureSession,
  type CaptureStep,
  WALLET_TOKEN_SCOPE,
} from '../../api/captures';
import { queryKeys } from '../../api/queries';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { Timestamp } from '../../components/DisplayValues';
import {
  KeyValueTable,
  Kpi,
  KpiStrip,
  PageHeader,
  PagingFooter,
  Section,
} from '../../components/Panels';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import { CalibrationResults } from './CalibrationResults';
import { CaptureCases } from './CaptureCases';
import { TokenVerdict } from './TokenVerdict';
import { CaptureOverview } from './CaptureOverview';
import { BudgetFields, CaptureStatus, CommandError, OperationNotice } from './CaptureShared';
import { formatWait } from './operationNotice';
import { reasonText } from './captureReasons';
import {
  captureKeys,
  isCaptureOperationActive,
  isCaptureOperationOpen,
  useCapture,
  useCaptureCommand,
  useCaptureConfig,
  useDeleteCapture,
} from './captureQueries';

const stageNames = { WALLET: 'Wallet', MARKET: 'Markets', RATES: 'Rates' } as const;
const stageKeys = { WALLET: 'wallet', MARKET: 'market', RATES: 'rates' } as const;
type Acquisition = keyof typeof stageNames;
const count = (value: number | null | undefined) =>
  value == null ? '—' : value.toLocaleString('en-US');
/** The latest inspection's reason for the acquisition stage an operation ran, or null for the other steps. */
const operationStageReason = (operation: CaptureOperation, inspect: CaptureSession['inspection']) =>
  operation.step in stageKeys ? (inspect?.[stageKeys[operation.step as Acquisition]].reason ?? null) : null;
function OperationProgress({
  operation,
  stageReason,
  onControl,
  controlPending,
}: {
  operation: CaptureOperation;
  /** The operation's stage reason from the latest inspection, shown when the operation itself has no code. */
  stageReason: string | null;
  onControl: (action: 'pause' | 'resume' | 'cancel' | 'retry') => void;
  controlPending: boolean;
}) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!isCaptureOperationActive(operation.state) && operation.state !== 'PAUSED') return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [operation.state]);
  const elapsed = Math.max(
    0,
    Math.floor(
      ((operation.endedAt ? Date.parse(operation.endedAt) : now) -
        Date.parse(operation.startedAt)) /
        1000,
    ),
  );
  const progress = operation.progress;
  const recovery = operation.recovery;
  const retrySeconds = recovery?.nextRetryAt
    ? Math.max(0, Math.ceil((Date.parse(recovery.nextRetryAt) - now) / 1000))
    : null;
  const controls = recovery?.enabled && (
    <Group gap="xs">
      {operation.state === 'RUNNING' && (
        <Button
          size="xs"
          variant="default"
          disabled={controlPending}
          onClick={() => onControl('pause')}
        >
          Pause remaining batch
        </Button>
      )}
      {operation.state === 'WAITING_RETRY' && (
        <>
          <Button
            size="xs"
            variant="default"
            disabled={controlPending}
            onClick={() => onControl('pause')}
          >
            Pause remaining batch
          </Button>
          <Button
            size="xs"
            variant="default"
            disabled={controlPending || (retrySeconds ?? 0) > 0}
            onClick={() => onControl('retry')}
          >
            Retry now
          </Button>
        </>
      )}
      {operation.state === 'PAUSED' && (
        <Button
          size="xs"
          variant="default"
          disabled={controlPending}
          onClick={() => onControl('resume')}
        >
          Resume remaining batch
        </Button>
      )}
      {isCaptureOperationActive(operation.state) && (
        <Button
          size="xs"
          color="red"
          variant="light"
          disabled={controlPending}
          onClick={() => onControl('cancel')}
        >
          Cancel remaining batch
        </Button>
      )}
    </Group>
  );
  return (
    <Section
      title="Latest operation"
      actions={
        <Group gap="sm">
          <CaptureStatus state={operation.state} />
          {controls}
        </Group>
      }
    >
      <Stack gap="md">
        <Group justify="space-between">
          <Text size="sm">
            {operation.step} ·{' '}
            {progress?.phase ??
              (isCaptureOperationActive(operation.state)
                ? 'Awaiting checkpoint'
                : 'No checkpoint recorded')}
          </Text>
          <Text size="xs" c="dimmed">
            Elapsed {elapsed}s · Last checkpoint{' '}
            <Timestamp iso={progress?.at ?? operation.updatedAt} />
          </Text>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <Section title="Budget for this run" padding="sm">
            <KeyValueTable
              rows={[
                [
                  'Requests',
                  `${count(progress?.operationRequests)} / ${count(operation.budget.requestBudget)}`,
                ],
                [
                  'Bytes',
                  `${count(progress?.operationBytes)} / ${count(operation.budget.byteBudget)}`,
                ],
                ['Deadline', `${operation.budget.operationTimeoutMinutes} min`],
              ]}
            />
          </Section>
          <Section title="Total used for this capture" padding="sm">
            <KeyValueTable
              rows={[
                ['Requests', count(progress?.cumulativeRequests)],
                ['Bytes', count(progress?.cumulativeBytes)],
                [
                  'Processed / selected',
                  `${count(progress?.processed)} / ${count(progress?.selected)}`,
                ],
              ]}
            />
          </Section>
        </SimpleGrid>
        {recovery?.enabled ? (
          <>
            <Section title="Recovery limits" padding="sm">
              <KeyValueTable
                rows={[
                  [
                    'Requests used / total',
                    `${count(recovery.requestsUsed)} / ${count(recovery.totalRequestBudget)}`,
                  ],
                  [
                    'Bytes used / total',
                    `${count(recovery.bytesUsed)} / ${count(recovery.totalByteBudget)}`,
                  ],
                  ['Attempts', `${count(recovery.attemptsUsed)} / ${count(recovery.maxAttempts)}`],
                  [
                    'Current reservation',
                    `${count(recovery.reservedRequests)} requests · ${count(recovery.reservedBytes)} bytes`,
                  ],
                ]}
              />
            </Section>
            {retrySeconds !== null && operation.state === 'WAITING_RETRY' && (
              <Text size="sm" role="status">
                Next retry in {formatWait(retrySeconds)}
              </Text>
            )}
          </>
        ) : (
          <Text size="xs" c="dimmed">
            Every explicit resume receives a fresh allowance. Total usage includes previous
            operations; checkpoints do not prove replay readiness.
          </Text>
        )}
        <OperationNotice operation={operation} stageReason={stageReason} />
      </Stack>
    </Section>
  );
}
function Summary({ values }: { values: Record<string, string> | undefined }) {
  return values && Object.keys(values).length ? (
    <KeyValueTable
      rows={Object.entries(values).map(([key, value]) => [
        key.replace(/([A-Z])/g, ' $1').replaceAll('.', ' · '),
        <Text size="sm" ff="monospace" key={key}>
          {value}
        </Text>,
      ])}
    />
  ) : (
    <Text size="sm" c="dimmed">
      No verified summary yet. Inspect evidence to refresh readiness.
    </Text>
  );
}
function AttemptHistory({ id, session }: { id: string; session: CaptureSession }) {
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: [...captureKeys.session(id), 'attempts', page],
    queryFn: ({ signal }) => capturesApi.attempts(id, page, signal),
  });
  return (
    <Section
      title="Attempt history"
      hint="Earlier evidence is retained. New attempts become current without starting acquisition."
    >
      {query.isPending ? (
        <LoadingState />
      ) : query.isError ? (
        <ErrorState error={query.error} />
      ) : (
        <>
          <ScrollArea>
            <Table miw={620}>
              <Table.Thead>
                <Table.Tr>
                  {['Stage', 'Attempt', 'Source', 'Created', 'Selection'].map((h) => (
                    <Table.Th key={h}>{h}</Table.Th>
                  ))}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {query.data.items.map((a) => (
                  <Table.Tr key={a.id}>
                    <Table.Td>{a.stage}</Table.Td>
                    <Table.Td ff="monospace">{a.id}</Table.Td>
                    <Table.Td>{a.attached ? 'Attached' : 'Managed'}</Table.Td>
                    <Table.Td>
                      <Timestamp iso={a.createdAt} />
                    </Table.Td>
                    <Table.Td>
                      {session.marketAttempt?.id === a.id || session.ratesAttempt?.id === a.id
                        ? 'Current'
                        : 'Retained'}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea>
          <PagingFooter
            page={page + 1}
            pages={Math.max(1, Math.ceil(query.data.total / 20))}
            total={query.data.total}
            onChange={(p) => setPage(p - 1)}
            noun="attempts"
          />
        </>
      )}
    </Section>
  );
}
function CaptureWorkspace({ session, config }: { session: CaptureSession; config: CaptureConfig }) {
  const initialState = useLocation().state as { recovery?: boolean } | null;
  const client = useQueryClient();
  const navigate = useNavigate();
  const deleteCapture = useDeleteCapture();
  const [selected, setSelected] = useState<string | null>('WALLET');
  const walletToken = session.wallet?.scope === WALLET_TOKEN_SCOPE;
  const [walletBudget, setWalletBudget] = useState<CaptureBudget>(
    walletToken ? config.walletTokenBudgetDefaults : config.budgetDefaults,
  );
  const [marketBudget, setMarketBudget] = useState<CaptureBudget>(config.budgetDefaults);
  const [walletRecoveryEnabled, setWalletRecoveryEnabled] = useState(
    Boolean(initialState?.recovery),
  );
  const [marketRecoveryEnabled, setMarketRecoveryEnabled] = useState(false);
  const [walletTotalRequests, setWalletTotalRequests] = useState(
    (walletToken ? config.walletTokenBudgetDefaults : config.budgetDefaults).requestBudget * 3,
  );
  const [walletTotalBytes, setWalletTotalBytes] = useState(
    (walletToken ? config.walletTokenBudgetDefaults : config.budgetDefaults).byteBudget * 3,
  );
  const [marketTotalRequests, setMarketTotalRequests] = useState(
    config.budgetDefaults.requestBudget * 3,
  );
  const [marketTotalBytes, setMarketTotalBytes] = useState(config.budgetDefaults.byteBudget * 3);
  const [maxAttempts, setMaxAttempts] = useState(3);
  const [scanPages, setScanPages] = useState(
    session.marketAttempt?.market?.scanPages ?? config.marketDefaults.scanPages,
  );
  const [capacity, setCapacity] = useState(config.marketDefaults.maxTransactions);
  const [newPages, setNewPages] = useState(config.marketDefaults.scanPages);
  const [validation, setValidation] = useState('');
  const refreshed = async () => {
    await client.invalidateQueries({ queryKey: captureKeys.all });
  };
  const operations = useCaptureCommand<CaptureOperationRequest, CaptureOperation>(
    `${session.id}:operation`,
    (body) => capturesApi.operate(session.id, body),
    async (operation) => {
      client.setQueryData<CaptureSession>(captureKeys.session(session.id), (old) =>
        old ? { ...old, operation } : old,
      );
      await refreshed();
    },
  );
  const controls = useCaptureCommand<
    CaptureOperationControlRequest & {
      operationId: string;
      action: 'pause' | 'resume' | 'cancel' | 'retry';
    },
    CaptureOperation
  >(
    `${session.id}:recovery-control`,
    (body) =>
      capturesApi.controlOperation(session.id, body.operationId, body.action, {
        requestId: body.requestId,
      }),
    async (operation) => {
      client.setQueryData<CaptureSession>(captureKeys.session(session.id), (old) =>
        old ? { ...old, operation } : old,
      );
      await refreshed();
    },
  );
  const attempts = useCaptureCommand<CaptureAttemptRequest, CaptureSession>(
    `${session.id}:attempt`,
    (body) => capturesApi.newAttempt(session.id, body),
    async (updated) => {
      client.setQueryData(captureKeys.session(session.id), updated);
      await refreshed();
    },
  );
  const imported = useMutation({
    mutationFn: () => capturesApi.import(session.id),
    onSuccess: async (result) => {
      client.setQueryData<CaptureSession>(captureKeys.session(session.id), (old) =>
        old ? { ...old, datasetId: result.dataset.id } : old,
      );
      await client.invalidateQueries({ queryKey: queryKeys.datasetsAll });
      await refreshed();
    },
  });
  const busy =
    isCaptureOperationOpen(session.operation?.state) ||
    operations.isPending ||
    controls.isPending ||
    attempts.isPending ||
    imported.isPending;
  const locked =
    busy || !!operations.pendingCommand || !!controls.pendingCommand || !!attempts.pendingCommand;
  const inspect = session.inspection;
  const start = (step: CaptureStep) => {
    const acquisition = step === 'WALLET' || step === 'MARKET';
    const budget = step === 'WALLET' ? walletBudget : marketBudget;
    const prefix = step === 'WALLET' ? 'wallet' : 'market';
    const recoveryEnabled = step === 'WALLET' ? walletRecoveryEnabled : marketRecoveryEnabled;
    const totalRequestBudget = step === 'WALLET' ? walletTotalRequests : marketTotalRequests;
    const totalByteBudget = step === 'WALLET' ? walletTotalBytes : marketTotalBytes;
    if (
      acquisition &&
      (budget.requestBudget < 1 ||
        budget.requestBudget > config.bounds[`${prefix}RequestsMax` as const] ||
        budget.byteBudget < 1 ||
        budget.byteBudget > config.bounds[`${prefix}BytesMax` as const] ||
        budget.operationTimeoutMinutes < 1 ||
        budget.operationTimeoutMinutes > config.bounds.operationTimeoutMinutesMax ||
        (step === 'MARKET' && (scanPages < 1 || scanPages > config.bounds.marketPagesMax)) ||
        (recoveryEnabled &&
          (totalRequestBudget < budget.requestBudget ||
            totalByteBudget < budget.byteBudget ||
            maxAttempts < 1 ||
            maxAttempts > config.bounds.recoveryAttemptsMax ||
            totalRequestBudget > config.bounds.recoveryRequestsMax ||
            totalByteBudget > config.bounds.recoveryBytesMax)))
    ) {
      setValidation(
        'Enter operation and recovery allowances within the shown limits. Total recovery budgets must cover at least one invocation.',
      );
      return;
    }
    setValidation('');
    const attemptId =
      step === 'WALLET'
        ? session.walletAttemptId
        : step === 'RATES'
          ? session.ratesAttempt?.id
          : session.marketAttempt?.id;
    operations.submit({
      step,
      ...(attemptId ? { attemptId } : {}),
      ...(acquisition ? { budget } : {}),
      ...(recoveryEnabled
        ? { recovery: { totalRequestBudget, totalByteBudget, maxAttempts } }
        : {}),
      ...(step === 'MARKET' ? { scanPages } : {}),
    });
  };
  const newAttempt = (stage: 'MARKET' | 'RATES') => {
    if (
      stage === 'MARKET' &&
      (capacity < 1 ||
        capacity > config.bounds.marketTransactionsMax ||
        newPages < 1 ||
        newPages > config.bounds.marketPagesMax)
    ) {
      setValidation('Enter new-attempt settings within the shown limits.');
      return;
    }
    setValidation('');
    attempts.submit({
      stage,
      ...(stage === 'MARKET' ? { market: { scanPages: newPages, maxTransactions: capacity } } : {}),
    });
  };
  return (
    <Stack gap="lg">
      <PageHeader
        title={session.displayName ?? session.name}
        description={
          session.wallet
            ? `${session.wallet.scope} · ${session.wallet.limit} sampled transaction(s)`
            : 'Attached capture evidence'
        }
        actions={
          <>
            <Button component={Link} to={paths.captures} variant="default">
              All captures
            </Button>
            <Button onClick={() => start('INSPECT')} disabled={locked} variant="default">
              Inspect evidence
            </Button>
            <ConfirmDelete
              label="Delete capture"
              title="Delete this capture?"
              disabled={isCaptureOperationOpen(session.operation?.state)}
              onConfirm={async () => {
                await deleteCapture.mutateAsync(session.id);
                await navigate(paths.captures);
              }}
            >
              Removes “{session.name}” and every file the console created for it: captured wallet,
              market and rates evidence, exports and calibration reports. Attached capture
              directories are only referenced and stay untouched, and datasets already imported stay
              in Datasets.
            </ConfirmDelete>
          </>
        }
      />
      <CaptureOverview session={session} config={config} />
      <StartWalletOnCreate session={session} config={config} />
      {session.wallet && (
        <Text ff="monospace" size="sm" style={{ overflowWrap: 'anywhere' }}>
          {session.wallet.wallet}
        </Text>
      )}
      {session.wallet?.mint && (
        <Text ff="monospace" size="sm" style={{ overflowWrap: 'anywhere' }}>
          Token mint {session.wallet.mint}
        </Text>
      )}
      <KpiStrip label="Capture stages">
        {(Object.keys(stageNames) as Acquisition[]).map((step) => (
          <Kpi
            key={step}
            label={stageNames[step]}
            hint={inspect?.[stageKeys[step]].reason ?? undefined}
          >
            <CaptureStatus state={inspect?.[stageKeys[step]].state ?? 'NOT_STARTED'} />
          </Kpi>
        ))}
      </KpiStrip>
      {(inspect?.targetToken || session.qualification) && (
        <TokenVerdict
          target={inspect?.targetToken}
          verdict={session.qualification}
          canQualify={walletToken && session.datasetReady}
          disabled={locked}
          onQualify={() => start('QUALIFY')}
        />
      )}
      {session.operation && (
        <OperationProgress
          operation={session.operation}
          stageReason={operationStageReason(session.operation, inspect)}
          onControl={(action) => {
            if (session.operation) controls.submit({ operationId: session.operation.id, action });
          }}
          controlPending={controls.isPending || !!controls.pendingCommand}
        />
      )}
      <CaptureCases sessionId={session.id} mainOperationState={session.operation?.state} />
      <CommandError
        error={operations.error}
        uncertain={!!operations.pendingCommand && !operations.isPending}
        retry={operations.retry}
      />
      <CommandError
        error={controls.error}
        uncertain={!!controls.pendingCommand && !controls.isPending}
        retry={controls.retry}
      />
      <CommandError
        error={attempts.error}
        uncertain={!!attempts.pendingCommand && !attempts.isPending}
        retry={attempts.retry}
      />
      {validation && (
        <Alert color="red" role="alert">
          {validation}
        </Alert>
      )}
      <Tabs value={selected} onChange={setSelected} keepMounted={false}>
        <Tabs.List>
          {(Object.keys(stageNames) as Acquisition[]).map((step) => (
            <Tabs.Tab key={step} value={step}>
              {stageNames[step]}
            </Tabs.Tab>
          ))}
          <Tabs.Tab value="DATASET">Dataset</Tabs.Tab>
          <Tabs.Tab value="CALIBRATION">Calibration</Tabs.Tab>
          <Tabs.Tab value="HISTORY">History</Tabs.Tab>
        </Tabs.List>
        {(Object.keys(stageNames) as Acquisition[]).map((step) => {
          const key = stageKeys[step];
          const stage = inspect?.[key];
          const stageBudget = step === 'WALLET' ? walletBudget : marketBudget;
          const configured = step === 'RATES' ? config.ratesConfigured : config.rpcConfigured;
          const canAcquire = !!stage && (stage.canStart || stage.canResume);
          // Over cap at the maximum capacity: a new attempt cannot help, only a smaller wallet sample can.
          const capacity = session.marketAttempt?.market?.maxTransactions;
          const reason =
            stage?.reason === 'OVER_CAP' &&
            capacity != null &&
            capacity >= config.bounds.marketTransactionsMax
              ? 'OVER_CAP_AT_MAXIMUM'
              : stage?.reason;
          const hasAttempt =
            step === 'WALLET' ||
            (step === 'MARKET' ? !!session.marketAttempt : !!session.ratesAttempt);
          const enabled = !locked && configured && canAcquire && hasAttempt;
          return (
            <Tabs.Panel key={step} value={step} pt="md">
              <Section
                title={`${stageNames[step]} capture`}
                hint={
                  step === 'MARKET'
                    ? `${config.windowSlots.toLocaleString('en-US')}-slot windows from each mint’s first accepted buy.`
                    : step === 'RATES'
                      ? 'Recorded SOL/USDT minutes for the fully verified current market attempt.'
                      : 'Wallet identity and scan bounds remain fixed after capture starts.'
                }
              >
                <Stack gap="md">
                  {!configured && (
                    <Alert color="gray">
                      {step === 'RATES'
                        ? 'Reference-rate acquisition is not configured.'
                        : 'Solana RPC acquisition is not configured.'}{' '}
                      Configure it in the backend process to enable this stage.
                    </Alert>
                  )}
                  {reasonText(reason) && (
                    <Alert color={stage?.state === 'INVALID' ? 'red' : 'amber'}>
                      {reasonText(reason)}
                    </Alert>
                  )}
                  <Summary values={inspect?.[`${key}Summary`]} />
                  {step === 'MARKET' &&
                    inspect?.marketWindows &&
                    inspect.marketWindows.length > 0 && (
                      <ScrollArea>
                        <Table miw={750}>
                          <Table.Thead>
                            <Table.Tr>
                              {[
                                'Mint',
                                'Slot window',
                                'Coverage',
                                'Scanned / selected',
                                'Detail gaps',
                                'Observations',
                              ].map((h) => (
                                <Table.Th key={h}>{h}</Table.Th>
                              ))}
                            </Table.Tr>
                          </Table.Thead>
                          <Table.Tbody>
                            {inspect.marketWindows.map((w) => (
                              <Table.Tr key={`${w.mint}:${w.fromSlot}`}>
                                <Table.Td>
                                  <Anchor
                                    href={`https://solscan.io/token/${encodeURIComponent(w.mint)}`}
                                    target="_blank"
                                    rel="noopener noreferrer"
                                  >
                                    {w.mint}
                                  </Anchor>
                                </Table.Td>
                                <Table.Td>
                                  {w.fromSlot}–{w.toSlot}
                                </Table.Td>
                                <Table.Td>{w.coverage}</Table.Td>
                                <Table.Td>
                                  {w.scanned} / {w.selected}
                                </Table.Td>
                                <Table.Td>{w.detailGaps ?? '—'}</Table.Td>
                                <Table.Td>{w.observations ?? '—'}</Table.Td>
                              </Table.Tr>
                            ))}
                          </Table.Tbody>
                        </Table>
                      </ScrollArea>
                    )}
                  {step !== 'RATES' && (
                    <>
                      <Accordion variant="separated">
                        <Accordion.Item value="budget">
                          <Accordion.Control>Advanced operation settings</Accordion.Control>
                          <Accordion.Panel>
                            <Stack gap="md">
                              <BudgetFields
                                value={step === 'WALLET' ? walletBudget : marketBudget}
                                onChange={step === 'WALLET' ? setWalletBudget : setMarketBudget}
                                config={config}
                                market={step === 'MARKET'}
                                disabled={locked}
                              />
                              {step === 'MARKET' && (
                                <NumberInput
                                  label="Market scan pages per window"
                                  value={scanPages}
                                  min={session.marketAttempt?.market?.scanPages ?? 1}
                                  max={config.bounds.marketPagesMax}
                                  allowDecimal={false}
                                  disabled={locked}
                                  onChange={(v) => setScanPages(Number(v))}
                                />
                              )}
                              <Checkbox
                                label="Enable automatic recovery for this stage"
                                checked={
                                  step === 'WALLET' ? walletRecoveryEnabled : marketRecoveryEnabled
                                }
                                onChange={(event) =>
                                  (step === 'WALLET'
                                    ? setWalletRecoveryEnabled
                                    : setMarketRecoveryEnabled)(event.currentTarget.checked)
                                }
                                disabled={locked}
                              />
                              {(step === 'WALLET'
                                ? walletRecoveryEnabled
                                : marketRecoveryEnabled) && (
                                <SimpleGrid cols={{ base: 1, sm: 3 }}>
                                  <NumberInput
                                    label="Total request budget"
                                    value={
                                      step === 'WALLET' ? walletTotalRequests : marketTotalRequests
                                    }
                                    min={stageBudget.requestBudget}
                                    max={config.bounds.recoveryRequestsMax}
                                    allowDecimal={false}
                                    disabled={locked}
                                    onChange={(value) =>
                                      (step === 'WALLET'
                                        ? setWalletTotalRequests
                                        : setMarketTotalRequests)(Number(value))
                                    }
                                  />
                                  <NumberInput
                                    label="Total byte budget"
                                    value={step === 'WALLET' ? walletTotalBytes : marketTotalBytes}
                                    min={stageBudget.byteBudget}
                                    max={config.bounds.recoveryBytesMax}
                                    allowDecimal={false}
                                    disabled={locked}
                                    onChange={(value) =>
                                      (step === 'WALLET'
                                        ? setWalletTotalBytes
                                        : setMarketTotalBytes)(Number(value))
                                    }
                                  />
                                  <NumberInput
                                    label="Maximum attempts"
                                    value={maxAttempts}
                                    min={1}
                                    max={config.bounds.recoveryAttemptsMax}
                                    allowDecimal={false}
                                    disabled={locked}
                                    onChange={(value) => setMaxAttempts(Number(value))}
                                  />
                                </SimpleGrid>
                              )}
                              <Text size="xs" c="dimmed">
                                {(step === 'WALLET' ? walletRecoveryEnabled : marketRecoveryEnabled)
                                  ? 'Automatic recovery can start another invocation after a recoverable stop. It stops at the total request and byte budgets or the attempt limit.'
                                  : 'Automatic recovery is off for this stage.'}{' '}
                                The allowance above remains the limit for each invocation.
                              </Text>
                              <Text size="sm" c="dimmed">
                                Budget for this run:{' '}
                                {count(
                                  (step === 'WALLET' ? walletBudget : marketBudget).requestBudget,
                                )}{' '}
                                requests ·{' '}
                                {count(
                                  (step === 'WALLET' ? walletBudget : marketBudget).byteBudget,
                                )}{' '}
                                bytes ·{' '}
                                {
                                  (step === 'WALLET' ? walletBudget : marketBudget)
                                    .operationTimeoutMinutes
                                }
                                -minute deadline.
                                {step === 'MARKET'
                                  ? ` ${scanPages} pages per window · ${count(session.marketAttempt?.market?.maxTransactions)} transaction capacity.`
                                  : ` ${session.wallet?.scanPages ?? '—'} scan pages.`}{' '}
                                {(step === 'WALLET' ? walletRecoveryEnabled : marketRecoveryEnabled)
                                  ? ' Automatic recovery renews the per-invocation allowance within the total recovery limits.'
                                  : ' The allowance renews only when you explicitly start or resume.'}
                              </Text>
                            </Stack>
                          </Accordion.Panel>
                        </Accordion.Item>
                      </Accordion>
                    </>
                  )}
                  <Group>
                    {(step !== 'WALLET' || !stage?.requiresNewAttempt) && (
                      <Button disabled={!enabled} onClick={() => start(step)}>
                        {stage?.canResume ? 'Resume' : 'Start'} {step === 'MARKET' ? 'market' : key}{' '}
                        capture
                      </Button>
                    )}
                    {!canAcquire && !stage?.reason && (
                      <Text size="xs" c="dimmed">
                        {stage?.state === 'READY'
                          ? 'This stage is verified and complete.'
                          : 'Inspect and satisfy the preceding stage before acquisition.'}
                      </Text>
                    )}
                  </Group>
                  {step !== 'WALLET' && (
                    <Section
                      title={`New ${step === 'MARKET' ? 'market' : 'rates'} attempt`}
                      padding="sm"
                      hint={
                        step === 'MARKET'
                          ? 'Keeps wallet evidence and prior attempts; starts an empty rates attempt and resets export, dataset and calibration.'
                          : 'Keeps market evidence and calibration; resets current export and dataset.'
                      }
                    >
                      <Stack gap="sm">
                        {step === 'MARKET' && (
                          <SimpleGrid cols={{ base: 1, sm: 2 }}>
                            <NumberInput
                              label="New attempt scan pages"
                              value={newPages}
                              min={1}
                              max={config.bounds.marketPagesMax}
                              disabled={locked}
                              allowDecimal={false}
                              onChange={(v) => setNewPages(Number(v))}
                            />
                            <NumberInput
                              label="New attempt transaction capacity"
                              value={capacity}
                              min={1}
                              max={config.bounds.marketTransactionsMax}
                              disabled={locked}
                              allowDecimal={false}
                              onChange={(v) => setCapacity(Number(v))}
                            />
                          </SimpleGrid>
                        )}
                        <Text size="xs" c="dimmed">
                          Capacity is a ceiling, not a target or cost estimate. Creating an attempt
                          performs no acquisition; the evidence is then inspected offline.
                        </Text>
                        <Button
                          variant="default"
                          disabled={
                            locked ||
                            (step === 'MARKET'
                              ? inspect?.wallet.state !== 'READY'
                              : !inspect?.canCalibrate)
                          }
                          onClick={() => newAttempt(step)}
                        >
                          {step === 'MARKET' ? 'New market attempt' : 'New rates attempt'}
                        </Button>
                      </Stack>
                    </Section>
                  )}
                </Stack>
              </Section>
            </Tabs.Panel>
          );
        })}
        <Tabs.Panel value="DATASET" pt="md">
          <Section
            title="Replay dataset"
            hint="Export verifies the current wallet, market windows and every required rate minute; schema v3 output is canonical."
          >
            <Stack gap="md">
              <Group>
                <Button onClick={() => start('EXPORT')} disabled={locked || !inspect?.canExport}>
                  Export dataset
                </Button>
                {session.datasetReady && (
                  <>
                    <Button
                      component="a"
                      href={capturesApi.download(session.id, 'dataset')}
                      variant="default"
                    >
                      Download dataset JSON
                    </Button>
                    <Button
                      onClick={() => imported.mutate()}
                      disabled={locked}
                      loading={imported.isPending}
                    >
                      Import dataset
                    </Button>
                  </>
                )}
              </Group>
              {!inspect?.canExport && (
                <Text size="sm" c="dimmed">
                  Verified matching wallet, complete market windows and recorded rate minutes are
                  required.
                </Text>
              )}
              {imported.isError && (
                <ErrorState error={imported.error} title="Dataset import failed" />
              )}
              {session.datasetId && (
                <Group>
                  <Button component={Link} variant="default" to={paths.dataset(session.datasetId)}>
                    View dataset
                  </Button>
                  <Button component={Link} to={paths.newRunFor(session.datasetId)}>
                    New run
                  </Button>
                </Group>
              )}
            </Stack>
          </Section>
        </Tabs.Panel>
        <Tabs.Panel value="CALIBRATION" pt="md">
          <Section
            title="Execution calibration"
            hint="Uses verified wallet and market evidence. Rates are not required."
            actions={
              <Button
                onClick={() => start('CALIBRATE')}
                disabled={locked || !inspect?.canCalibrate}
              >
                Calibrate execution
              </Button>
            }
          >
            {session.calibrationReady && session.calibration ? (
              <CalibrationResults report={session.calibration} id={session.id} />
            ) : (
              <Text size="sm" c="dimmed">
                Complete and verify matching wallet and market evidence, then generate a calibration
                report.
              </Text>
            )}
          </Section>
        </Tabs.Panel>
        <Tabs.Panel value="HISTORY" pt="md">
          <AttemptHistory id={session.id} session={session} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
export function CaptureDetailPage() {
  const { id = '' } = useParams();
  const session = useCapture(id);
  const config = useCaptureConfig();
  if (session.isPending || config.isPending) return <LoadingState label="Loading capture…" />;
  if (session.isError || config.isError)
    return (
      <ErrorState
        error={session.error ?? config.error}
        title="Could not load capture"
        onRetry={() => {
          void session.refetch();
          void config.refetch();
        }}
      />
    );
  return <CaptureWorkspace key={id} session={session.data} config={config.data} />;
}

function StartWalletOnCreate({
  session,
  config,
}: {
  session: CaptureSession;
  config: CaptureConfig;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const sent = useRef(false);
  const state = location.state as { startWallet?: boolean; recovery?: boolean } | null;
  const command = useCaptureCommand<CaptureOperationRequest, CaptureOperation>(
    `${session.id}:create-start`,
    (body) => capturesApi.operate(session.id, body),
    async () => {
      await client.invalidateQueries({ queryKey: captureKeys.all });
    },
  );
  useEffect(() => {
    if (!state?.startWallet || sent.current || isCaptureOperationActive(session.operation?.state))
      return;
    sent.current = true;
    const budget =
      session.wallet?.scope === WALLET_TOKEN_SCOPE
        ? config.walletTokenBudgetDefaults
        : config.budgetDefaults;
    command.submit({
      step: 'WALLET',
      attemptId: session.walletAttemptId,
      budget,
      ...(state.recovery
        ? {
            recovery: {
              totalRequestBudget: budget.requestBudget * 3,
              totalByteBudget: budget.byteBudget * 3,
              maxAttempts: 3,
            },
          }
        : {}),
    });
    void navigate(location.pathname, { replace: true, state: null });
  }, [state, session, config, command, navigate, location.pathname]);
  return (
    <CommandError
      error={command.error}
      uncertain={!!command.pendingCommand && !command.isPending}
      retry={command.retry}
    />
  );
}
