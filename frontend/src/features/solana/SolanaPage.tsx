import {
  Alert,
  Badge,
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
import { IconAlert, IconCheck, IconSatellite, IconX } from '../../components/Icons';
import { KeyValueTable, PageHeader, Section } from '../../components/Panels';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { isSolanaAddress } from '../../lib/base58';
import { formatSlot } from '../../lib/format';
import { clusterColor, outcomeColor, outcomeLabel } from './solanaOutcome';

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

function CheckResult({ check }: { check: SolanaCheck }) {
  const color = outcomeColor(check.outcome);
  return (
    <Stack gap="sm">
      <Group gap="sm">
        <Badge color={color} variant="filled" size="lg">
          {outcomeLabel(check.outcome)}
        </Badge>
        {check.cached && (
          <Badge color="gray" variant="outline" title="Results are cached for 30 s so repeated clicks do not spend RPC quota">
            Cached result
          </Badge>
        )}
        <Text size="sm" c="dimmed">
          Checked <Timestamp iso={check.checkedAt} size="sm" />
        </Text>
      </Group>
      <KeyValueTable
        rows={[
          ['Latency', check.latencyMs == null ? '—' : `${check.latencyMs} ms`],
          ['Node version', check.version ?? '—'],
          ['Slot', formatSlot(check.slot)],
          [
            'Cluster',
            check.cluster ? (
              <Badge key="c" color={clusterColor(check.cluster)} variant="light" tt="none">
                {check.cluster}
              </Badge>
            ) : (
              '—'
            ),
          ],
          ...(check.rpcErrorCode != null ? [['RPC error code', String(check.rpcErrorCode)] as [string, string]] : []),
        ]}
      />
      {check.message && (
        <Alert color={color === 'teal' ? 'gray' : color} variant="light" p="xs">
          <Text size="sm">{check.message}</Text>
        </Alert>
      )}
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
  const color = outcomeColor(result.outcome);
  return (
    <Stack gap="sm">
      <Group gap="sm">
        <Badge color={color} variant="filled" size="lg">
          {outcomeLabel(result.outcome)}
        </Badge>
        {result.cached && (
          <Badge color="gray" variant="outline">
            Cached result
          </Badge>
        )}
        <Text size="sm" c="dimmed">
          Checked <Timestamp iso={result.checkedAt} size="sm" />
        </Text>
      </Group>
      <KeyValueTable
        rows={[
          ['Address', <ShortId key="a" value={result.address} />],
          ['Signature', result.signature ? <ShortId key="s" value={result.signature} /> : '—'],
          ['Slot', formatSlot(result.slot)],
          ['Transaction version', result.transactionVersion ?? '—'],
          ['Signatures examined', result.signaturesExamined == null ? '—' : String(result.signaturesExamined)],
          ...(result.rpcErrorCode != null ? [['RPC error code', String(result.rpcErrorCode)] as [string, string]] : []),
        ]}
      />
      {result.message && (
        <Alert color={color === 'teal' ? 'gray' : color} variant="light" p="xs">
          <Text size="sm">{result.message}</Text>
        </Alert>
      )}
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
        Fetches the newest 5 signatures of an address and the newest successful transaction, then
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
            style={{ flex: '1 1 340px' }}
          />
          <Button type="submit" mt={25} loading={check.isPending} disabled={!configured}>
            Check address
          </Button>
        </Group>
      </form>
      {!configured && (
        <Text size="sm" c="dimmed">
          Available once an RPC is configured.
        </Text>
      )}
      {check.isError && !serverError?.fields.address && (
        <Alert color={serverError?.code === 'SOLANA_NOT_CONFIGURED' ? 'gray' : 'red'} variant="light" icon={<IconAlert />} role="alert">
          {errorMessage(check.error)}
        </Alert>
      )}
      {check.data && <AddressResult result={check.data} />}
    </Stack>
  );
}

export function SolanaPage() {
  const status = useSolanaStatus();
  const check = useSolanaCheck();
  const data = status.data;
  const latest = check.data ?? data?.lastCheck ?? null;

  return (
    <Stack gap="lg">
      <PageHeader
        title="Solana"
        description="Read-only connectivity checks. Nothing here signs or sends a transaction; checks run only when you click."
      />
      {status.isPending ? (
        <LoadingState label="Loading Solana status…" />
      ) : status.isError ? (
        <ErrorState error={status.error} title="Could not load the Solana status" onRetry={() => void status.refetch()} />
      ) : data ? (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          <Section
            title="RPC status"
            actions={
              <Button onClick={() => check.mutate()} loading={check.isPending} variant={data.configured ? 'filled' : 'default'}>
                Run connectivity check
              </Button>
            }
          >
            <Stack gap="md">
              <KeyValueTable
                rows={[
                  [
                    'Configured',
                    <Badge key="c" color={data.configured ? 'teal' : 'gray'} variant="light">
                      {data.configured ? 'Yes' : 'Not configured'}
                    </Badge>,
                  ],
                  ['Host', data.host ?? '—'],
                  [
                    'Cluster',
                    data.cluster ? (
                      <Badge key="cl" color={clusterColor(data.cluster)} variant="light" tt="none">
                        {data.cluster}
                      </Badge>
                    ) : (
                      '—'
                    ),
                  ],
                  ['Last check', data.lastCheck ? <Timestamp key="lc" iso={data.lastCheck.checkedAt} /> : 'Never'],
                ]}
              />
              {!data.configured && <NotConfiguredHelp />}
              {check.isError && <ErrorState error={check.error} title="The check could not run" />}
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
      ) : null}
    </Stack>
  );
}
