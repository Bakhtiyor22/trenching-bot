import { Alert, Button, Group, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import type { Recommendation } from '../api/setup';
import { codeLabel, recommendationCopy } from '../lib/codes';
import { Section } from './Panels';
import { CodeText } from './PlainLanguage';

function dismissed(id: string): boolean {
  try {
    return localStorage.getItem(`rec-dismissed:${id}`) === 'true';
  } catch {
    return false;
  }
}
export function Recommendations({
  items,
  onApply,
  onUndo,
  onAdjust,
}: {
  items: Recommendation[];
  onApply: (item: Recommendation) => void;
  onUndo?: (item: Recommendation) => void;
  onAdjust?: (item: Recommendation) => void;
}) {
  const [hidden, setHidden] = useState<string[]>(() =>
    items.filter((item) => dismissed(item.id)).map((item) => item.id),
  );
  const [applied, setApplied] = useState<string[]>([]);
  const [storageUnavailable, setStorageUnavailable] = useState(false);
  function hide(ids: string[]) {
    setHidden((previous) => [...new Set([...previous, ...ids])]);
    try {
      ids.forEach((id) => localStorage.setItem(`rec-dismissed:${id}`, 'true'));
    } catch {
      setStorageUnavailable(true);
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
        {visible.map((item) => (
          <Stack
            key={item.id}
            gap="xs"
            pb="sm"
            style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}
          >
            <Text fw={600} size="sm">
              {recommendationCopy[item.id]?.title ?? codeLabel(item.id)}
            </Text>
            <Text size="sm" c="dimmed">
              {recommendationCopy[item.id]?.why ?? item.id}
            </Text>
            {item.caveatCode && (
              <Alert color="yellow">
                {codeLabel(item.caveatCode)}
                <CodeText code={item.caveatCode} />
              </Alert>
            )}
            <Group gap="xs">
              {(item.setting || item.id === 'ADD_LEGACY_COMPARISON') && (
                <Button
                  size="xs"
                  variant="light"
                  onClick={() => {
                    if (applied.includes(item.id) && onUndo) {
                      onUndo(item);
                      setApplied(applied.filter((id) => id !== item.id));
                    } else {
                      onApply(item);
                      if (onUndo) setApplied([...applied, item.id]);
                    }
                  }}
                >
                  {applied.includes(item.id) ? 'Undo' : 'Apply'}
                </Button>
              )}
              {onAdjust && item.setting && (
                <Button size="xs" variant="subtle" onClick={() => onAdjust(item)}>
                  Adjust
                </Button>
              )}
              <Button size="xs" variant="subtle" onClick={() => hide([item.id])}>
                Dismiss
              </Button>
            </Group>
          </Stack>
        ))}
        {storageUnavailable && (
          <Text size="xs" role="status">
            Browser storage is unavailable; dismissed suggestions stay hidden for this visit.
          </Text>
        )}
      </Stack>
    </Section>
  );
}
