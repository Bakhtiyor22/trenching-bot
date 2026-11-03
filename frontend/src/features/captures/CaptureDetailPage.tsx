import {
  Accordion,
  ActionIcon,
  Alert,
  Anchor,
  Button,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  ScrollArea,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
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
import { RelativeTime, ShortId, Timestamp } from '../../components/DisplayValues';
import { Meter } from '../../components/Meter';
import { KeyValueTable, PageHeader, PagingFooter, Section } from '../../components/Panels';
import { Term } from '../../components/PlainLanguage';
import { QueryView, RefreshNotice } from '../../components/QueryView';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { ApiError } from '../../api/client';
import { captureScopeLabels } from '../../lib/codes';
import {
  formatBytes,
  formatCount,
  formatDuration,
  formatSlot,
  humanizeCode,
  mintLabel,
} from '../../lib/format';
import type { GlossaryTerm } from '../../lib/glossary';
import { pumpFunTokenHref, solscanTokenHref } from '../../lib/marketLinks';
import { paths } from '../../paths';
import { CalibrationResults } from './CalibrationResults';
import { CaptureCases } from './CaptureCases';
import { TokenVerdict } from './TokenVerdict';
import { CaptureOverview, CaptureStepper } from './CaptureOverview';
import {
  BudgetFields,
  CaptureStatusChip,
  CommandError,
  LiveCue,
  OperationNotice,
} from './CaptureShared';
import { operationStateLabel, operationStepLabels, summaryKeyLabel } from './captureLabels';
import { formatWait } from './operationNotice';
import { reasonText } from './captureReasons';
import {
  captureKeys,
  capturePollInterval,
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
/** The Advanced tabs, by their `?tab=` value. */
const ADVANCED_TABS = ['WALLET', 'MARKET', 'RATES', 'DATASET', 'CALIBRATION', 'HISTORY'] as const;
type AdvancedTab = (typeof ADVANCED_TABS)[number];
/** How many animation frames "Turn on automatic resume" waits for its checkbox to become focusable. */
const RECOVERY_FOCUS_FRAMES = 30;
/** Attempts per history page; the backend's default page size. */
const ATTEMPTS_PAGE_SIZE = 20;
/** The terms this page's evidence is described in; each opens its meaning. */
const PAGE_TERMS: GlossaryTerm[] = [
  'Market window',
  'Slot',
  'Request',
  'Gap',
  'Cutoff',
  'Validated',
];
/** Steps that only read saved files: their card shows no download counters. */
const OFFLINE_STEP_NAMES: Partial<Record<CaptureStep, string>> = {
  INSPECT: 'saved-file check',
  EXPORT: 'dataset export',
  CALIBRATE: 'calibration',
  QUALIFY: 'qualification',
};

function advancedTab(value: string | null): AdvancedTab | null {
  const tab = value?.toUpperCase();
  return ADVANCED_TABS.find((item) => item === tab) ?? null;
}

/** The latest inspection's reason for the acquisition stage an operation ran, or null for the other steps. */
const operationStageReason = (
  operation: CaptureOperation,
  inspect: CaptureSession['inspection'],
) =>
  operation.step in stageKeys
    ? (inspect?.[stageKeys[operation.step as Acquisition]].reason ?? null)
    : null;

/** "Cancel remaining batch" behind a confirmation: cancelling stops the stage's remaining work. */
function CancelBatch({ disabled, onConfirm }: { disabled: boolean; onConfirm: () => void }) {
  const [opened, { open, close }] = useDisclosure(false);
  return (
    <>
      <Button size="xs" color="red" variant="outline" disabled={disabled} onClick={open}>
        Cancel remaining batch
      </Button>
      <Modal opened={opened} onClose={close} title="Cancel the remaining batch?" centered>
        <Stack gap="md">
          <Text size="sm">
            The rest of this step&apos;s work stops now. Everything already saved is kept, and you
            can start the step again later with a fresh allowance.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              Keep running
            </Button>
            <Button
              color="red"
              onClick={() => {
                onConfirm();
                close();
              }}
            >
              Cancel batch
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

function OperationProgress({
  operation,
  sessionId,
  stageReason,
  onControl,
  controlPending,
}: {
  operation: CaptureOperation;
  sessionId: string;
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
  const offlineName = OFFLINE_STEP_NAMES[operation.step];
  const offline = offlineName !== undefined;
  // The wait is shown in words ("20 min"), so it is computed here from the same 1 s tick as the
  // run time; RetryCountdown only words it in seconds.
  const retrySeconds = recovery?.nextRetryAt
    ? Math.max(0, Math.ceil((Date.parse(recovery.nextRetryAt) - now) / 1000))
    : null;
  const controls = recovery?.enabled && (
    <Group gap="xs">
      {(operation.state === 'RUNNING' || operation.state === 'WAITING_RETRY') && (
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
        <Button
          size="xs"
          variant="default"
          disabled={controlPending || (retrySeconds ?? 0) > 0}
          onClick={() => onControl('retry')}
        >
          Retry now
        </Button>
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
        <CancelBatch disabled={controlPending} onConfirm={() => onControl('cancel')} />
      )}
    </Group>
  );
  const lastProgressAt = progress?.at ?? operation.updatedAt;
  return (
    <Section
      title={
        offlineName ? `Latest ${offlineName}` : `${operationStepLabels[operation.step]} progress`
      }
      actions={controls}
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Last progress <RelativeTime iso={lastProgressAt} /> (
          <Timestamp iso={lastProgressAt} c="dimmed" />)
        </Text>
        {offline ? (
          <Text size="sm">
            {offlineName?.replace(/^./, (c) => c.toUpperCase())} ·{' '}
            {operationStateLabel(operation.state)} · ran {formatDuration(elapsed * 1000)}. Offline;
            no provider requests.
          </Text>
        ) : (
          <>
            <Meter
              label="Stage progress"
              value={progress?.processed ?? 0}
              max={progress?.selected ?? 0}
            />
            <Text size="sm">
              {formatCount(progress?.processed)} of {formatCount(progress?.selected)} transactions
            </Text>
            <KeyValueTable
              rows={[
                [
                  'Requests, this run',
                  `${formatCount(progress?.operationRequests)} / ${formatCount(operation.budget.requestBudget)}`,
                ],
                ['Requests, whole capture', formatCount(progress?.cumulativeRequests)],
                ['Downloaded', formatBytes(progress?.cumulativeBytes)],
                ['Run time', formatDuration(elapsed * 1000)],
              ]}
            />
            <Anchor
              component={Link}
              to={`${paths.usage}?captureId=${encodeURIComponent(sessionId)}`}
              size="sm"
            >
              See this capture's API usage
            </Anchor>
            {recovery?.enabled ? (
              <Stack gap="xs">
                <Text size="sm" fw={600}>
                  Recovery limits
                </Text>
                <KeyValueTable
                  rows={[
                    [
                      'Requests used / total',
                      `${formatCount(recovery.requestsUsed)} / ${formatCount(recovery.totalRequestBudget)}`,
                    ],
                    [
                      'Data used / total',
                      `${formatBytes(recovery.bytesUsed)} / ${formatBytes(recovery.totalByteBudget)}`,
                    ],
                    [
                      'Attempts',
                      `${formatCount(recovery.attemptsUsed)} / ${formatCount(recovery.maxAttempts)}`,
                    ],
                    [
                      'Current reservation',
                      `${formatCount(recovery.reservedRequests)} requests · ${formatBytes(recovery.reservedBytes)}`,
                    ],
                  ]}
                />
                {retrySeconds !== null && operation.state === 'WAITING_RETRY' && (
                  <Text size="sm" role="status">
                    Next retry in {formatWait(retrySeconds)}
                  </Text>
                )}
              </Stack>
            ) : (
              <Text size="xs" c="dimmed">
                Every explicit resume receives a fresh allowance. Total usage includes previous
                operations; checkpoints do not prove replay readiness.
              </Text>
            )}
          </>
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
        summaryKeyLabel(key),
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
      <QueryView query={query} errorTitle="Could not load attempts">
        {(data) => (
          <>
            <ScrollArea type="auto">
              <Table miw={620} verticalSpacing="xs">
                <Table.Thead>
                  <Table.Tr>
                    {['Stage', 'Attempt', 'Source', 'Created', 'Selection'].map((h) => (
                      <Table.Th key={h}>{h}</Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {data.items.map((a) => (
                    <Table.Tr key={a.id}>
                      <Table.Td>{operationStepLabels[a.stage]}</Table.Td>
                      <Table.Td>
                        <ShortId value={a.id} />
                      </Table.Td>
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
              pages={Math.max(1, Math.ceil(data.total / ATTEMPTS_PAGE_SIZE))}
              total={data.total}
              onChange={(p) => setPage(p - 1)}
              noun="attempts"
            />
          </>
        )}
      </QueryView>
    </Section>
  );
}
function CaptureWorkspace({
  session,
  config,
  notice,
}: {
  session: CaptureSession;
  config: CaptureConfig;
  /** Shown under the header when the latest refresh failed and the page shows older data. */
  notice?: ReactNode;
}) {
  const initialState = useLocation().state as { recovery?: boolean } | null;
  const [params, setParams] = useSearchParams();
  const client = useQueryClient();
  const navigate = useNavigate();
  const deleteCapture = useDeleteCapture();
  const [editingName, setEditingName] = useState(false);
  const [name, setName] = useState(session.name);
  const urlTab = advancedTab(params.get('tab'));
  const [advancedOpen, setAdvancedOpen] = useState(urlTab !== null);
  const [settingsOpen, setSettingsOpen] = useState<string | null>(null);
  const walletRecoveryRef = useRef<HTMLInputElement>(null);
  const marketRecoveryRef = useRef<HTMLInputElement>(null);
  const selected: AdvancedTab = urlTab ?? 'WALLET';
  const setSelected = (tab: string | null) => {
    const next = advancedTab(tab);
    if (!next) return;
    setParams(
      (current) => {
        const updated = new URLSearchParams(current);
        updated.set('tab', next.toLowerCase());
        return updated;
      },
      { replace: true },
    );
  };
  const walletToken = session.wallet?.scope === WALLET_TOKEN_SCOPE;
  const [walletBudget, setWalletBudget] = useState<CaptureBudget>(
    walletToken ? config.walletTokenBudgetDefaults : config.budgetDefaults,
  );
  const [marketBudget, setMarketBudget] = useState<CaptureBudget>(config.budgetDefaults);
  const [walletRecoveryEnabled, setWalletRecoveryEnabled] = useState(
    Boolean(
      initialState?.recovery ||
      (session.operation?.step === 'WALLET' && session.operation.recovery?.enabled),
    ),
  );
  const [marketRecoveryEnabled, setMarketRecoveryEnabled] = useState(
    Boolean(session.operation?.step === 'MARKET' && session.operation.recovery?.enabled),
  );
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
  const rename = useCaptureCommand<{ requestId: string; name: string }, CaptureSession>(
    `${session.id}:rename`,
    (body) => capturesApi.rename(session.id, body),
    async () => {
      setEditingName(false);
      await refreshed();
    },
  );
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
  const enableRecovery = () => {
    const wallet = session.summary?.currentStep.stage === 'WALLET';
    setSelected(wallet ? 'WALLET' : 'MARKET');
    (wallet ? setWalletRecoveryEnabled : setMarketRecoveryEnabled)(true);
    setAdvancedOpen(true);
    setSettingsOpen('budget');
    // The Advanced panel and the stage tab open over a few frames; focus once the checkbox can take it.
    let frames = 0;
    const focusRecovery = () => {
      const checkbox = wallet ? walletRecoveryRef.current : marketRecoveryRef.current;
      checkbox?.focus();
      if (checkbox && document.activeElement === checkbox) {
        checkbox.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
      } else if (++frames < RECOVERY_FOCUS_FRAMES) {
        window.requestAnimationFrame(focusRecovery);
      }
    };
    window.requestAnimationFrame(focusRecovery);
  };
  const summaryBudget =
    session.summary?.currentStep.stage === 'WALLET' ? walletBudget : marketBudget;
  const summaryRecovery =
    session.summary?.currentStep.stage === 'WALLET' ? walletRecoveryEnabled : marketRecoveryEnabled;
  const walletLabel =
    config.suggestedWallets?.find((item) => item.address === session.wallet?.wallet)?.label ??
    config.recentWallets?.find((item) => item.address === session.wallet?.wallet)?.label ??
    'Wallet';
  const title = session.displayName ?? session.name;
  return (
    <>
      <PageHeader
        breadcrumbs={[{ label: 'Captures', to: paths.captures }, { label: title }]}
        title={editingName ? 'Rename capture' : title}
        status={
          <Group gap="xs" wrap="nowrap">
            {!editingName && (
              <ActionIcon
                variant="subtle"
                color="gray"
                aria-label="Rename capture"
                onClick={() => {
                  setName(session.name);
                  setEditingName(true);
                }}
              >
                ✎
              </ActionIcon>
            )}
            {session.summary && <CaptureStatusChip status={session.summary.status} />}
            <LiveCue active={!!capturePollInterval(session)} />
          </Group>
        }
        meta={
          <>
            {session.wallet ? (
              <>
                {walletLabel} · <ShortId value={session.wallet.wallet} size="xs" />
                {session.wallet.mint && (
                  <>
                    {' · '}
                    <Anchor
                      href={pumpFunTokenHref(session.wallet.mint)}
                      target="_blank"
                      rel="noopener noreferrer"
                      size="xs"
                    >
                      Token {mintLabel(session.wallet.mint)} ↗
                    </Anchor>
                  </>
                )}
                {' · '}
                {captureScopeLabels[session.wallet.scope] ?? humanizeCode(session.wallet.scope)}
              </>
            ) : (
              'Attached capture evidence'
            )}
            {' · Created '}
            <Timestamp iso={session.createdAt} size="xs" c="dimmed" />
          </>
        }
        actions={
          <>
            <Button onClick={() => start('INSPECT')} disabled={locked} variant="default">
              Re-check saved files
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
      <Stack gap="lg">
        {notice}
        {editingName && (
          <form
            aria-label="Rename capture"
            onSubmit={(event) => {
              event.preventDefault();
              rename.submit({ name: name.trim() });
            }}
          >
            <Stack gap="xs">
              <Group align="flex-end">
                <TextInput
                  label="Capture name"
                  value={name}
                  autoFocus
                  onChange={(event) => setName(event.currentTarget.value)}
                  maxLength={120}
                  required
                  w={{ base: '100%', sm: 420 }}
                  error={rename.error instanceof ApiError ? rename.error.fields.name : undefined}
                />
                <Button type="submit" loading={rename.isPending} disabled={!!rename.pendingCommand}>
                  Save name
                </Button>
                <Button variant="subtle" onClick={() => setEditingName(false)}>
                  Cancel
                </Button>
              </Group>
              {rename.error instanceof ApiError && rename.error.suggestion && (
                <Group>
                  <Button
                    variant="default"
                    onClick={() =>
                      setName(
                        rename.error instanceof ApiError ? (rename.error.suggestion ?? name) : name,
                      )
                    }
                  >
                    Use suggestion
                  </Button>
                </Group>
              )}
              <CommandError
                error={rename.error}
                uncertain={!!rename.pendingCommand && !rename.isPending}
                retry={rename.retry}
              />
            </Stack>
          </form>
        )}
        <CaptureOverview session={session} config={config} onEnableRecovery={enableRecovery} />
        <StartWalletOnCreate session={session} config={config} />
        <CaptureStepper session={session} />
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
            sessionId={session.id}
            stageReason={operationStageReason(session.operation, inspect)}
            onControl={(action) => {
              if (session.operation) controls.submit({ operationId: session.operation.id, action });
            }}
            controlPending={controls.isPending || !!controls.pendingCommand}
          />
        )}
        <CaptureCases
          sessionId={session.id}
          mainOperationState={session.operation?.state}
          session={session}
          config={config}
          onResume={() => {
            setAdvancedOpen(true);
            setSelected('MARKET');
          }}
        />
        <Text component="aside" aria-label="Terms on this page" size="xs" c="dimmed">
          Terms on this page:{' '}
          {PAGE_TERMS.map((term, index) => (
            <span key={term}>
              {index > 0 && ' · '}
              <Term>{term}</Term>
            </span>
          ))}
        </Text>
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
        <Accordion
          variant="separated"
          value={advancedOpen ? 'advanced' : null}
          onChange={(value) => setAdvancedOpen(value === 'advanced')}
        >
          <Accordion.Item value="advanced">
            <Accordion.Control>
              <Text component="span" fw={600}>
                Advanced: limits, attempts and history
              </Text>
              <Text size="xs" c="dimmed" mt={4}>
                Per run: {formatCount(summaryBudget.requestBudget)} requests ·{' '}
                {formatBytes(summaryBudget.byteBudget)} · {summaryBudget.operationTimeoutMinutes}{' '}
                min · Automatic resume: {summaryRecovery ? 'on' : 'off'} · Market cutoff:{' '}
                {session.marketAttempt?.market?.cutoffMode === 'DECIDED'
                  ? 'decided (old defaults)'
                  : 'full'}
              </Text>
            </Accordion.Control>
            <Accordion.Panel>
              <Tabs value={selected} onChange={setSelected} keepMounted={false}>
                <Tabs.List style={{ flexWrap: 'wrap' }}>
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
                  const configured =
                    step === 'RATES' ? config.ratesConfigured : config.rpcConfigured;
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
                            ? `${formatCount(config.windowSlots)}-slot windows from each mint’s first accepted buy.`
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
                            <Alert color={stage?.state === 'INVALID' ? 'red' : 'orange'}>
                              {reasonText(reason)}
                            </Alert>
                          )}
                          <Summary values={inspect?.[`${key}Summary`]} />
                          {step === 'MARKET' &&
                            inspect?.marketWindows &&
                            inspect.marketWindows.length > 0 && (
                              <ScrollArea>
                                <Table miw={750} verticalSpacing="xs">
                                  <Table.Thead>
                                    <Table.Tr>
                                      {[
                                        'Mint',
                                        'Slot window',
                                        'Coverage',
                                        'Scanned / selected',
                                        'Detail gaps',
                                        'Observations',
                                      ].map((h, index) => (
                                        <Table.Th key={h} ta={index >= 3 ? 'right' : undefined}>
                                          {h}
                                        </Table.Th>
                                      ))}
                                    </Table.Tr>
                                  </Table.Thead>
                                  <Table.Tbody>
                                    {inspect.marketWindows.map((w) => (
                                      <Table.Tr key={`${w.mint}:${w.fromSlot}`}>
                                        <Table.Td>
                                          <Anchor
                                            href={solscanTokenHref(w.mint)}
                                            target="_blank"
                                            rel="noopener noreferrer"
                                            title={w.mint}
                                            ff="monospace"
                                            size="sm"
                                          >
                                            {mintLabel(w.mint)}
                                          </Anchor>
                                        </Table.Td>
                                        <Table.Td ff="monospace" style={{ whiteSpace: 'nowrap' }}>
                                          {formatSlot(w.fromSlot)}–{formatSlot(w.toSlot)}
                                        </Table.Td>
                                        <Table.Td>{humanizeCode(w.coverage)}</Table.Td>
                                        <Table.Td ta="right" ff="monospace">
                                          {formatCount(w.scanned)} / {formatCount(w.selected)}
                                        </Table.Td>
                                        <Table.Td ta="right" ff="monospace">
                                          {formatCount(w.detailGaps)}
                                        </Table.Td>
                                        <Table.Td ta="right" ff="monospace">
                                          {formatCount(w.observations)}
                                        </Table.Td>
                                      </Table.Tr>
                                    ))}
                                  </Table.Tbody>
                                </Table>
                              </ScrollArea>
                            )}
                          {step !== 'RATES' && (
                            <>
                              <Accordion
                                variant="separated"
                                value={settingsOpen}
                                onChange={setSettingsOpen}
                              >
                                <Accordion.Item value="budget">
                                  <Accordion.Control>Advanced operation settings</Accordion.Control>
                                  <Accordion.Panel>
                                    <Stack gap="md">
                                      <BudgetFields
                                        value={stageBudget}
                                        onChange={
                                          step === 'WALLET' ? setWalletBudget : setMarketBudget
                                        }
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
                                        ref={
                                          step === 'WALLET' ? walletRecoveryRef : marketRecoveryRef
                                        }
                                        label="Enable automatic recovery for this stage"
                                        checked={
                                          step === 'WALLET'
                                            ? walletRecoveryEnabled
                                            : marketRecoveryEnabled
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
                                              step === 'WALLET'
                                                ? walletTotalRequests
                                                : marketTotalRequests
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
                                            value={
                                              step === 'WALLET'
                                                ? walletTotalBytes
                                                : marketTotalBytes
                                            }
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
                                        {(
                                          step === 'WALLET'
                                            ? walletRecoveryEnabled
                                            : marketRecoveryEnabled
                                        )
                                          ? 'Automatic recovery can start another invocation after a recoverable stop. It stops at the total request and byte budgets or the attempt limit.'
                                          : 'Automatic recovery is off for this stage.'}{' '}
                                        The allowance above remains the limit for each invocation.
                                      </Text>
                                      <Text size="sm" c="dimmed">
                                        Budget for this run:{' '}
                                        {formatCount(stageBudget.requestBudget)} requests ·{' '}
                                        {formatBytes(stageBudget.byteBudget)} ·{' '}
                                        {stageBudget.operationTimeoutMinutes}
                                        -minute deadline.
                                        {step === 'MARKET'
                                          ? ` ${scanPages} pages per window · ${formatCount(session.marketAttempt?.market?.maxTransactions)} transaction capacity.`
                                          : ` ${session.wallet?.scanPages ?? '—'} scan pages.`}{' '}
                                        {(
                                          step === 'WALLET'
                                            ? walletRecoveryEnabled
                                            : marketRecoveryEnabled
                                        )
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
                              <Button
                                variant="default"
                                disabled={!enabled}
                                onClick={() => start(step)}
                              >
                                {stage?.canResume ? 'Resume' : 'Start'}{' '}
                                {step === 'MARKET' ? 'market' : key} capture
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
                                  Capacity is a ceiling, not a target or cost estimate. Creating an
                                  attempt performs no acquisition; the evidence is then inspected
                                  offline.
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
                        <Button
                          variant="default"
                          onClick={() => start('EXPORT')}
                          disabled={locked || !inspect?.canExport}
                        >
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
                              variant="default"
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
                          Verified matching wallet, complete market windows and recorded rate
                          minutes are required.
                        </Text>
                      )}
                      {imported.isError && (
                        <ErrorState error={imported.error} title="Dataset import failed" />
                      )}
                      {session.datasetId && (
                        <Group>
                          <Button
                            component={Link}
                            variant="default"
                            to={paths.dataset(session.datasetId)}
                          >
                            View dataset
                          </Button>
                          <Button
                            component={Link}
                            variant="default"
                            to={paths.newRunFor(session.datasetId)}
                          >
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
                        variant="default"
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
                        Complete and verify matching wallet and market evidence, then generate a
                        calibration report.
                      </Text>
                    )}
                  </Section>
                </Tabs.Panel>
                <Tabs.Panel value="HISTORY" pt="md">
                  <AttemptHistory id={session.id} session={session} />
                </Tabs.Panel>
              </Tabs>
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      </Stack>
    </>
  );
}
export function CaptureDetailPage() {
  const { id = '' } = useParams();
  const session = useCapture(id);
  const config = useCaptureConfig();
  const retry = () => {
    void session.refetch();
    void config.refetch();
  };
  // Data first: a failed background refetch keeps the page, and any rename or form input, on screen.
  if (session.data === undefined || config.data === undefined) {
    const failed =
      (session.data === undefined && session.isError) ||
      (config.data === undefined && config.isError);
    return failed ? (
      <ErrorState
        error={session.error ?? config.error}
        title="Could not load capture"
        onRetry={retry}
      />
    ) : (
      <LoadingState label="Loading capture…" />
    );
  }
  const failedRefresh = session.isError ? session : config.isError ? config : null;
  return (
    <CaptureWorkspace
      key={id}
      session={session.data}
      config={config.data}
      notice={
        failedRefresh && (
          <RefreshNotice
            error={failedRefresh.error}
            updatedAt={failedRefresh.dataUpdatedAt}
            onRetry={retry}
          />
        )
      }
    />
  );
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
