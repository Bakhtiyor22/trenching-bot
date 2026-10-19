import { Alert, Button, Center, Group, Loader, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { ApiError, errorMessage } from '../api/client';
import { IconAlert, IconInfo } from './Icons';

export function LoadingState({ label = 'Loading…' }: { label?: string }) {
  return (
    <Center py="xl" role="status" aria-live="polite">
      <Group gap="sm">
        <Loader size="sm" />
        <Text c="dimmed" size="sm">
          {label}
        </Text>
      </Group>
    </Center>
  );
}

export function ErrorState({
  error,
  title = 'Something went wrong',
  onRetry,
}: {
  error: unknown;
  title?: string;
  onRetry?: () => void;
}) {
  return (
    <Alert color="red" variant="light" title={title} icon={<IconAlert />} role="alert">
      <Stack gap="xs" align="flex-start">
        <Text size="sm">{errorMessage(error)}</Text>
        {onRetry && (
          <Button size="xs" variant="default" onClick={onRetry}>
            Try again
          </Button>
        )}
      </Stack>
    </Alert>
  );
}

export function RetryCountdown({
  until,
  onTick,
}: {
  until: number;
  onTick?: (now: number) => void;
}) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => {
      const next = Date.now();
      setNow(next);
      onTick?.(next);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [onTick]);
  return (
    <Text size="sm" role="status">
      {Math.max(0, Math.ceil((until - now) / 1000))} seconds until you can try again.
    </Text>
  );
}

function RateLimitedRetry({
  seconds,
  retryAt,
  onRetry,
}: {
  seconds: number;
  retryAt?: number;
  onRetry?: () => void;
}) {
  const [fallbackUntil] = useState(() => Date.now() + Math.max(0, seconds) * 1000);
  const until = retryAt ?? fallbackUntil;
  const [now, setNow] = useState(Date.now);
  return (
    <>
      <RetryCountdown until={until} onTick={setNow} />
      {onRetry && (
        <Button size="xs" variant="default" disabled={now < until} onClick={onRetry}>
          Try now
        </Button>
      )}
    </>
  );
}

export function ProviderErrorState({
  error,
  retryAt,
  onRetry,
  alternatives,
}: {
  error: unknown;
  retryAt?: number;
  onRetry?: () => void;
  alternatives?: ReactNode;
}) {
  const code = error instanceof ApiError ? error.code : 'PROVIDER_UNAVAILABLE';
  const copy: Record<string, { title: string; body: string }> = {
    RPC_NOT_CONFIGURED: {
      title: 'Solana RPC is not set up',
      body: 'Set the SOLANA_RPC_URL environment variable for the backend process and restart it. Its value is never shown here.',
    },
    PROVIDER_RATE_LIMITED: {
      title: 'The provider is busy (rate limited)',
      body: 'Your saved actions are kept. Wait for the provider’s pause to end, then try again.',
    },
    PROVIDER_UNAVAILABLE: {
      title: 'The provider is unavailable',
      body: 'The provider could not answer. Your saved actions are kept; try again in a moment.',
    },
    NOT_MAINNET: {
      title: 'Solana mainnet is required',
      body: 'Configure the backend to use Solana mainnet before loading wallet activity.',
    },
    LOCAL_ACCESS_REQUIRED: {
      title: 'Local access is required',
      body: 'Open the console on the backend machine to use this feature.',
    },
  };
  const message = copy[code] ?? {
    title: 'The provider is unavailable',
    body: 'The provider could not answer. Your saved actions are kept; try again in a moment.',
  };
  const seconds = error instanceof ApiError ? error.retryAfterSeconds : undefined;
  return (
    <Alert color="amber" title={message.title} role="alert">
      <Stack gap="xs" align="flex-start">
        <Text size="sm">{message.body}</Text>
        {code === 'PROVIDER_RATE_LIMITED' && seconds !== undefined ? (
          <RateLimitedRetry
            key={`${seconds}:${error instanceof Error ? error.message : ''}`}
            seconds={seconds}
            retryAt={retryAt}
            onRetry={onRetry}
          />
        ) : (
          onRetry && (
            <Button size="xs" variant="default" onClick={onRetry}>
              Try now
            </Button>
          )
        )}
        {alternatives}
        <Text size="xs" c="dimmed" ff="monospace">
          Technical code: {code}
        </Text>
      </Stack>
    </Alert>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Center py="xl">
      <Stack align="center" gap="xs" maw={420} ta="center">
        <IconInfo size={28} color="var(--mantine-color-dimmed)" />
        <Text fw={600}>{title}</Text>
        {description && (
          <Text size="sm" c="dimmed">
            {description}
          </Text>
        )}
        {action}
      </Stack>
    </Center>
  );
}
