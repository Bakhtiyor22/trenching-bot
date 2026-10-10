import { Anchor, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';
import { locallyStartedRuns, useLatestRuns } from '../api/queries';
import { formatDecimal, pnlColor } from '../lib/decimal';
import { mintLabel, shortId } from '../lib/format';
import { paths } from '../paths';

/**
 * Announces runs that appear while the console is open but were started elsewhere: another tab, or
 * someone else on a shared link. The first poll only records what already exists.
 */
export function RunWatcher() {
  const latest = useLatestRuns();
  const navigate = useNavigate();
  const seen = useRef<Set<string> | null>(null);

  useEffect(() => {
    const items = latest.data?.items;
    if (!items) return;
    if (seen.current === null) {
      seen.current = new Set(items.map((run) => run.id));
      return;
    }
    for (const run of items) {
      if (seen.current.has(run.id)) continue;
      seen.current.add(run.id);
      if (locallyStartedRuns.has(run.id)) continue;
      notifications.show({
        id: `run-${run.id}`,
        color: pnlColor(run.realizedPnl) === 'red' ? 'red' : 'teal',
        title: 'New run finished',
        autoClose: 8000,
        message: (
          <Text size="sm">
            {/* Notifications render outside the router, so the link navigates through a captured callback. */}
            <Anchor
              component="button"
              type="button"
              ff="monospace"
              size="sm"
              onClick={() => {
                notifications.hide(`run-${run.id}`);
                void navigate(paths.run(run.id));
              }}
            >
              {shortId(run.id)}
            </Anchor>{' '}
            · {run.closedCount} closed ·{' '}
            <Text span ff="monospace" fw={600} c={pnlColor(run.realizedPnl)}>
              {formatDecimal(run.realizedPnl)} {mintLabel(run.quoteMint)}
            </Text>
          </Text>
        ),
      });
    }
  }, [latest.data, navigate]);

  return null;
}
