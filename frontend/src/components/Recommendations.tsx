import { Alert, Badge, Button, Group, Stack, Text } from '@mantine/core';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import type { Recommendation } from '../api/setup';
import { errorMessage } from '../api/client';
import { codeLabel, recommendationCopy } from '../lib/codes';
import { paths } from '../paths';
import { Section } from './Panels';

function dismissed(id: string): boolean {
  try {
    return localStorage.getItem(`rec-dismissed:${id}`) === 'true';
  } catch {
    return false;
  }
}

function itemCopy(item: Recommendation) {
  const { facts } = item;
  const base = recommendationCopy[item.id] ?? {
    title: codeLabel(item.id),
    why: codeLabel(item.id),
  };
  if (item.id === 'ADD_LEGACY_COMPARISON')
    return {
      ...base,
      why: `One click starts a second run with ${facts.dipPercent}% / +${facts.takeProfitPercent}% / −${facts.stopLossPercent}%, then opens both side by side.`,
    };
  if (item.id === 'ENABLE_RECOVERY')
    return {
      ...base,
      why: `A single run is capped at ${facts.budget ?? facts.requestBudget ?? facts.requests} requests. With recovery on, the capture continues by itself up to the total you set, instead of stopping and waiting for you.`,
    };
  return base;
}

function settingLabel(item: Recommendation): string {
  switch (item.id) {
    case 'SCOPE_WALLET_TOKEN':
      return 'Scope: wallet + token';
    case 'ENABLE_RECOVERY':
      return `Recovery: ${item.facts.requests} requests, ${item.facts.attempts} attempts`;
    case 'CUTOFF_DECIDED':
      return 'Market cutoff: once the result is known (old defaults)';
    case 'DECU_PUMPFUN_SCOPE':
      return 'Scope: Pump.fun buys';
    case 'USE_AGREED_STRATEGY':
      return 'Settings: agreed strategy';
    default:
      return item.setting ? `${codeLabel(item.setting.field)}: ${item.setting.value}` : '';
  }
}

export function Recommendations({
  items,
  onApply,
  onUndo,
  onAdjust,
  appliedIds,
}: {
  items: Recommendation[];
  onApply: (item: Recommendation) => void | Promise<void>;
  onUndo?: (item: Recommendation) => void;
  onAdjust?: (item: Recommendation) => void;
  appliedIds?: string[];
}) {
  const [hidden, setHidden] = useState<string[]>(() =>
    items.filter((item) => dismissed(item.id)).map((item) => item.id),
  );
  const [applied, setApplied] = useState<string[]>([]);
  const [pending, setPending] = useState<string[]>([]);
  const [completed, setCompleted] = useState<string[]>([]);
  const inflight = useRef(new Set<string>());
  const [actionError, setActionError] = useState<string | null>(null);
  const [storageUnavailable, setStorageUnavailable] = useState(false);
  function hide(ids: string[]) {
    setHidden((previous) => [...new Set([...previous, ...ids])]);
    try {
      ids.forEach((id) => localStorage.setItem(`rec-dismissed:${id}`, 'true'));
    } catch {
      setStorageUnavailable(true);
    }
  }
  async function applyAction(item: Recommendation) {
    if (inflight.current.has(item.id) || completed.includes(item.id)) return;
    inflight.current.add(item.id);
    setPending((previous) => [...previous, item.id]);
    setActionError(null);
    try {
      await onApply(item);
      setCompleted((previous) => [...previous, item.id]);
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      inflight.current.delete(item.id);
      setPending((previous) => previous.filter((id) => id !== item.id));
    }
  }
  const visible = items.filter((item) => !hidden.includes(item.id) && !dismissed(item.id));
  return (
    <Section
      title="Suggestions"
      actions={
        <Button
          variant="subtle"
          size="xs"
          disabled={!visible.length}
          onClick={() => hide(items.map((item) => item.id))}
        >
          Hide all
        </Button>
      }
    >
      <Stack>
        {!visible.length && (
          <Text size="sm" c="dimmed">
            No suggestions to show.
          </Text>
        )}
        {visible.map((item) => {
          const action = item.id === 'ADD_LEGACY_COMPARISON' || item.id === 'SINGLE_TOKEN_DATASET';
          const isApplied = (appliedIds ?? applied).includes(item.id);
          const copy = itemCopy(item);
          return (
            <Stack
              key={item.id}
              gap="xs"
              pb="sm"
              style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}
            >
              <Group gap="xs">
                <Text fw={600} size="sm">
                  {copy.title}
                </Text>
                {!action && (
                  <Badge tt="none" color={isApplied ? 'teal' : 'gray'}>
                    {isApplied ? 'Applied' : 'Suggested'}
                  </Badge>
                )}
              </Group>
              <Text size="sm" c="dimmed">
                {copy.why}
              </Text>
              {!action && item.setting && <Text size="sm">Sets {settingLabel(item)}</Text>}
              {item.caveatCode && (
                <Alert color="yellow">
                  {item.id === 'CUTOFF_DECIDED'
                    ? `Today the cutoff is decided with the old default rules (${item.facts.dipPercent}% dip, +${item.facts.takeProfitPercent}% / −${item.facts.stopLossPercent}%), not the agreed strategy.`
                    : codeLabel(item.caveatCode)}
                </Alert>
              )}
              <Group gap="xs">
                {item.id === 'ADD_LEGACY_COMPARISON' ? (
                  <Button
                    size="xs"
                    loading={pending.includes(item.id)}
                    disabled={pending.includes(item.id) || completed.includes(item.id)}
                    onClick={() => void applyAction(item)}
                  >
                    Add comparison run
                  </Button>
                ) : item.id === 'SINGLE_TOKEN_DATASET' ? (
                  <Button size="xs" component={Link} to={paths.newCapture}>
                    Capture another token
                  </Button>
                ) : (
                  item.setting && (
                    <Button
                      size="xs"
                      variant="light"
                      onClick={() => {
                        if (isApplied && onUndo) {
                          onUndo(item);
                          setApplied((previous) => previous.filter((id) => id !== item.id));
                        } else {
                          void onApply(item);
                          if (onUndo) setApplied((previous) => [...previous, item.id]);
                        }
                      }}
                    >
                      {isApplied && onUndo ? 'Undo' : 'Apply'}
                    </Button>
                  )
                )}
                {!action && onAdjust && item.setting && (
                  <Button size="xs" variant="subtle" onClick={() => onAdjust(item)}>
                    Adjust
                  </Button>
                )}
                {!action && (
                  <Button size="xs" variant="subtle" onClick={() => hide([item.id])}>
                    Dismiss
                  </Button>
                )}
              </Group>
            </Stack>
          );
        })}
        {actionError && (
          <Alert color="red" role="alert">
            {actionError}
          </Alert>
        )}
        <Text size="xs" c="dimmed">
          Suggestions only change settings when you apply them. Dismissed ones stay hidden on this
          device.
        </Text>
        {storageUnavailable && (
          <Text size="xs" role="status">
            Browser storage is unavailable; dismissed suggestions stay hidden for this visit.
          </Text>
        )}
      </Stack>
    </Section>
  );
}
