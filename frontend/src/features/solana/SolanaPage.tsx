import {
  Alert,
  Button,
  Code,
  Group,
  List,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
} from '@mantine/core';
import { useState, type FormEvent } from 'react';
import { ApiError, errorMessage } from '../../api/client';
import { useAddressCheck, useSolanaCheck, useSolanaStatus } from '../../api/queries';
import type { AddressCheck, SolanaCheck } from '../../api/types';
import { ShortId, Timestamp } from '../../components/DisplayValues';
import { IconCheck, IconInfo, IconSatellite, IconX } from '../../components/Icons';
import { KeyValueTable, PageHeader, Section } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { QueryView } from '../../components/QueryView';
import { ProviderErrorState } from '../../components/StateViews';
import { StatusChip } from '../../components/StatusChip';
import { isSolanaAddress } from '../../lib/base58';
import { formatCount, formatSlot } from '../../lib/format';
import {
  clusterLabel,
  isKnownOutcome,
  outcomeAlertColor,
  outcomeLabel,
  outcomeTone,
  toneGlyph,
} from './solanaOutcome';

/** Error code of an address check when no RPC is configured: a setup state, not a provider failure. */
const NOT_CONFIGURED_CODE = 'SOLANA_NOT_CONFIGURED';
const CACHE_NOTE = 'Results are cached for 30 s so repeated clicks do not spend RPC quota';

function NotConfiguredHelp() {
  return (
    <Alert color="gray" variant="light" title="No Solana RPC configured" icon={<IconSatellite />}>
      <Text size="sm">
        The connectivity panel is optional. To enable it, set the <Code>SOLANA_RPC_URL</Code>{' '}
        environment variable for the backend process and restart it. The URL is treated as a secret:
        the console never asks for it and only ever shows the host.
      </Text>
    </Alert>
  );
}

/** An outcome as a chip (glyph and word); an outcome without a written label also shows its code. */
function OutcomeChip({ outcome }: { outcome: string }) {
  const tone = outcomeTone(outcome);
  return (
    <Stack gap={2}>
      <StatusChip tone={tone} glyph={toneGlyph(tone)}>
        {outcomeLabel(outcome)}
      </StatusChip>
      {!isKnownOutcome(outcome) && <CodeText code={outcome} />}
    </Stack>
  );
}

/** A cluster name in words, neutral for every cluster, with the name the node reported as its code. */
function Cluster({ cluster }: { cluster: string | null | undefined }) {
  if (!cluster) return <>—</>;
  return (
    <Stack gap={2} align="flex-start">
      <StatusChip tone="neutral">{clusterLabel(cluster)}</StatusChip>
      <CodeText code={cluster} />
    </Stack>
  );
}

function ResultHeader({ outcome, cached, checkedAt }: { outcome: string; cached: boolean; checkedAt: string }) {
  return (
    <Group gap="sm" align="flex-start">
      <OutcomeChip outcome={outcome} />
      {cached && (
        <StatusChip tone="neutral" glyph="◷" title={CACHE_NOTE}>
          Cached result
        </StatusChip>
      )}
      <Text size="sm" c="dimmed">
        Checked <Timestamp iso={checkedAt} size="sm" />
      </Text>
    </Group>
  );
}

function OutcomeMessage({ outcome, message }: { outcome: string; message: string | null }) {
  if (!message) return null;
  return (
    <Alert color={outcomeAlertColor(outcome)} variant="light" p="xs">
      <Text size="sm">{message}</Text>
    </Alert>
  );
}

function CheckResult({ check }: { check: SolanaCheck }) {
  return (
    <Stack gap="sm">
      <ResultHeader outcome={check.outcome} cached={check.cached} checkedAt={check.checkedAt} />
      <KeyValueTable
        rows={[
          ['Latency', check.latencyMs == null ? '—' : `${formatCount(check.latencyMs)} ms`],
          ['Node version', check.version ?? '—'],
          ['Slot', formatSlot(check.slot)],
          ['Cluster', <Cluster key="c" cluster={check.cluster} />],
          ...(check.rpcErrorCode != null
            ? [['RPC error code', String(check.rpcErrorCode)] as [string, string]]
            : []),
        ]}
      />
      <OutcomeMessage outcome={check.outcome} message={check.message} />
    </Stack>
  );
}

function FieldChecklist({ result }: { result: AddressCheck }) {
  const rows = [
    ...result.presentFields.map((field) => ({ field, present: true })),
    ...result.missingFields.map((field) => ({ field, present: false })),
  ];
  if (rows.length === 0) return null;
  return (
    <List spacing={6} size="sm" aria-label="Normalizer fields">
      {rows.map(({ field, present }) => (
        <List.Item
          key={field}
          icon={
            <ThemeIcon color={present ? 'teal' : 'red'} size={20} radius="xl" variant="light">
              {present ? <IconCheck size={13} /> : <IconX size={13} />}
            </ThemeIcon>
          }
        >
          <Code>{field}</Code>{' '}
          <Text span size="xs" c={present ? 'dimmed' : 'red'}>
            {present ? 'present' : 'missing'}
          </Text>
        </List.Item>
      ))}
    </List>
  );
}

function AddressResult({ result }: { result: AddressCheck }) {
  return (
    <Stack gap="sm">
      <ResultHeader outcome={result.outcome} cached={result.cached} checkedAt={result.checkedAt} />
      <KeyValueTable
        rows={[
          ['Address', <ShortId key="a" value={result.address} />],
          ['Signature', result.signature ? <ShortId key="s" value={result.signature} /> : '—'],
          ['Slot', formatSlot(result.slot)],
          ['Transaction version', result.transactionVersion ?? '—'],
          ['Signatures examined', formatCount(result.signaturesExamined)],
          ...(result.rpcErrorCode != null
            ? [['RPC error code', String(result.rpcErrorCode)] as [string, string]]
            : []),
        ]}
      />
      <OutcomeMessage outcome={result.outcome} message={result.message} />
      {result.outcome === 'NO_SUCCESSFUL_TRANSACTION' && (
        <Text size="sm" c="dimmed">
          None of the newest signatures was a successful transaction, so no fields could be inspected.
        </Text>
      )}
      <FieldChecklist result={result} />
    </Stack>
  );
}

function AddressCheckForm({ configured }: { configured: boolean }) {
  const [address, setAddress] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const check = useAddressCheck();
  const serverError = check.error instanceof ApiError ? check.error : null;
  const fieldError = clientError ?? serverError?.fields.address ?? null;
  const retry = () => {
    if (check.variables) check.mutate(check.variables);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const value = address.trim();
    if (!isSolanaAddress(value)) {
      setClientError('Enter a base58 Solana address (32 bytes)');
      return;
    }
    setClientError(null);
    check.mutate(value);
  };

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        Fetches the newest 10 signatures of an address and the newest successful transaction, then
        reports whether the fields the wallet normalizer needs are present. Read-only.
      </Text>
      <form onSubmit={submit} noValidate>
        <Group align="flex-start" gap="sm">
          <TextInput
            label="Address"
            placeholder="Base58 wallet or program address"
            value={address}
            onChange={(event) => {
              setAddress(event.currentTarget.value);
              setClientError(null);
            }}
            error={fieldError}
            ff="monospace"
            autoComplete="off"
            spellCheck={false}
            style={{ flex: '1 1 240px', minWidth: 0 }}
          />
          <Button type="submit" variant="default" mt={25} loading={check.isPending} disabled={!configured}>
            Check address
          </Button>
        </Group>
      </form>
      {!configured && (
        <Text size="sm" c="dimmed">
          Available once an RPC is configured.
        </Text>
      )}
      {check.isError &&
        !serverError?.fields.address &&
        (serverError?.code === NOT_CONFIGURED_CODE ? (
          <Alert color="gray" variant="light" icon={<IconInfo />} role="alert">
            {errorMessage(check.error)}
          </Alert>
        ) : (
          <ProviderErrorState error={check.error} onRetry={retry} />
        ))}
      {check.data && <AddressResult result={check.data} />}
    </Stack>
  );
}

export function SolanaPage() {
  const status = useSolanaStatus();
  const check = useSolanaCheck();

  return (
    <>
      <PageHeader
        title="Solana"
        description="Read-only connectivity checks. Nothing here signs or sends a transaction; checks run only when you click."
      />
      <QueryView query={status} loadingLabel="Loading Solana status…" errorTitle="Could not load the Solana status">
        {(data) => {
          const latest = check.data ?? data.lastCheck ?? null;
          return (
            <SimpleGrid cols={{ base: 1, lg: 2 }}>
              <Section
                title="RPC status"
                actions={
                  <Button
                    onClick={() => check.mutate()}
                    loading={check.isPending}
                    variant={data.configured ? 'filled' : 'default'}
                  >
                    Run connectivity check
                  </Button>
                }
              >
                <Stack gap="md">
                  <KeyValueTable
                    rows={[
                      [
                        'Configured',
                        data.configured ? (
                          <StatusChip key="c" tone="success" glyph="✓">
                            Yes
                          </StatusChip>
                        ) : (
                          <StatusChip key="c" tone="neutral" glyph="–">
                            Not configured
                          </StatusChip>
                        ),
                      ],
                      ['Host', data.host ?? '—'],
                      ['Cluster', <Cluster key="cl" cluster={data.cluster} />],
                      [
                        'Last check',
                        data.lastCheck ? <Timestamp key="lc" iso={data.lastCheck.checkedAt} /> : 'Never',
                      ],
                    ]}
                  />
                  {!data.configured && <NotConfiguredHelp />}
                  {check.isError && <ProviderErrorState error={check.error} onRetry={() => check.mutate()} />}
                  {latest && <CheckResult check={latest} />}
                  <Text size="xs" c="dimmed">
                    Calls getHealth, getVersion, getSlot and getGenesisHash. Results are cached for 30 s
                    and concurrent clicks share one request.
                  </Text>
                </Stack>
              </Section>
              <Section title="Address check">
                <AddressCheckForm configured={data.configured} />
              </Section>
            </SimpleGrid>
          );
        }}
      </QueryView>
    </>
  );
}
