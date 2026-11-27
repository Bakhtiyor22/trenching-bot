import { Button, Group, ScrollArea, SegmentedControl, Stack, Table, Text } from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import {
  useAutomationVariations,
  type AutomationStatus,
  type AutomationVariation,
} from '../../api/automation';
import { Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { formatCount } from '../../lib/format';
import { paths } from '../../paths';
import { variationText } from './automationModel';
import {
  AutomationFrame,
  VariationLink,
  VariationStateChip,
  ZoneNote,
  ZoneTime,
} from './AutomationShared';

/** The tab in the URL; All is the default and is left out. An old `?archived=1` link opens Archived. */
const SHOW_PARAM = 'show';
const LEGACY_ARCHIVED_PARAM = 'archived';
const TABS = ['all', 'active', 'archived'] as const;
type Tab = (typeof TABS)[number];
const TAB_LABELS: Record<Tab, string> = { all: 'All', active: 'Active', archived: 'Archived' };
/** An archived row stays readable but recedes behind the active ones. */
const ARCHIVED_OPACITY = 0.7;

function readTab(params: URLSearchParams): Tab {
  const value = params.get(SHOW_PARAM);
  if (TABS.includes(value as Tab)) return value as Tab;
  return params.get(LEGACY_ARCHIVED_PARAM) === '1' ? 'archived' : 'all';
}

function VariationRows({
  variations,
  status,
  baseline,
}: {
  variations: AutomationVariation[];
  status: AutomationStatus;
  baseline: string | null;
}) {
  return (
    <>
      {variations.map((v) => {
        const archived = v.state === 'ARCHIVED';
        return (
          <Table.Tr
            key={v.label}
            data-archived={archived || undefined}
            style={archived ? { opacity: ARCHIVED_OPACITY } : undefined}
          >
            <Table.Th scope="row" fw={400} className="console-sticky">
              <Group gap={6} wrap="nowrap">
                <VariationLink label={v.label} />
                {v.label === baseline && (
                  <Text span size="xs" c="dimmed">
                    baseline
                  </Text>
                )}
              </Group>
            </Table.Th>
            <Table.Td>
              <Text size="sm" c={archived ? 'dimmed' : undefined}>
                {variationText(v)}
              </Text>
            </Table.Td>
            <Table.Td>
              <VariationStateChip state={v.state} />
            </Table.Td>
            <Table.Td>
              {v.activeFrom ? (
                <ZoneTime iso={v.activeFrom} zone={status.zone} withDate />
              ) : (
                <Text size="sm" c="dimmed">
                  plan start
                </Text>
              )}
            </Table.Td>
            <Table.Td>
              {archived ? (
                <Stack gap={0}>
                  <ZoneTime iso={v.archivedAt} zone={status.zone} withDate />
                  <Text size="xs" c="dimmed">
                    {v.replacedByLabel ? (
                      <>
                        replaced by <VariationLink label={v.replacedByLabel} size="xs" />
                      </>
                    ) : (
                      'on its own, nothing replaces it'
                    )}
                  </Text>
                </Stack>
              ) : (
                <Text size="sm" c="dimmed">
                  —
                </Text>
              )}
            </Table.Td>
            <Table.Td>
              {v.replacesLabel ? (
                <VariationLink label={v.replacesLabel} />
              ) : (
                <Text size="sm" c="dimmed">
                  —
                </Text>
              )}
            </Table.Td>
          </Table.Tr>
        );
      })}
    </>
  );
}

function Variations({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const tab = readTab(params);
  const list = useAutomationVariations(null);
  const setTab = (next: Tab) =>
    setParams(
      (prev) => {
        const url = new URLSearchParams(prev);
        url.delete(LEGACY_ARCHIVED_PARAM);
        if (next === 'all') url.delete(SHOW_PARAM);
        else url.set(SHOW_PARAM, next);
        return url;
      },
      { replace: true },
    );
  const items = list.data?.items ?? [];
  const counts: Record<Tab, number> = {
    all: items.length,
    active: items.filter((v) => v.state === 'ACTIVE').length,
    archived: items.filter((v) => v.state === 'ARCHIVED').length,
  };
  const baseline =
    items.find((v) => v.state === 'ACTIVE' && v.differsFromAgreed.length === 0)?.label ?? null;

  return (
    <Section
      title={`Variations${list.data ? ` · plan ${list.data.planId}` : ''}`}
      hint="Each active variation is replayed on every captured buy. An edit archives a variation and saves its next version (V03 → V03.1); a new variation takes the next base number. Archiving stops a variation without a replacement. An archived variation keeps its runs and days, and is never replayed again."
      actions={<ZoneNote zone={status.zone} />}
    >
      <Stack gap="sm">
        <ScrollArea type="never" maw="100%">
          <SegmentedControl
            aria-label="Show variations"
            value={tab}
            onChange={(value) => setTab(value as Tab)}
            data={TABS.map((t) => ({
              value: t,
              label: (
                <Group gap={6} wrap="nowrap" justify="center">
                  <span>{TAB_LABELS[t]}</span>{' '}
                  {list.data && (
                    <Text span size="xs" ff="monospace" c="dimmed">
                      {formatCount(counts[t])}
                    </Text>
                  )}
                </Group>
              ),
            }))}
          />
        </ScrollArea>
        <QueryView
          query={list}
          loadingLabel="Loading variations…"
          errorTitle="Variations unavailable"
        >
          {(data) => {
            const shown = data.items.filter(
              (v) =>
                tab === 'all' || (tab === 'active' ? v.state === 'ACTIVE' : v.state === 'ARCHIVED'),
            );
            if (data.items.length === 0)
              return (
                <EmptyState
                  title="No variation yet"
                  description="The backend seeds the plan's variations at startup; none is stored for this plan yet."
                />
              );
            if (shown.length === 0)
              return (
                <EmptyState
                  title={tab === 'archived' ? 'No variation archived yet' : 'No active variation'}
                  description="An edit or Archive on a variation's page archives it; it then shows here."
                />
              );
            return (
              <ScrollArea type="auto">
                <Table miw={880} verticalSpacing="xs" aria-label="Variations">
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th className="console-sticky">Variation</Table.Th>
                      <Table.Th>Changes from the agreed strategy</Table.Th>
                      <Table.Th>State</Table.Th>
                      <Table.Th>Runs since</Table.Th>
                      <Table.Th>Archived</Table.Th>
                      <Table.Th>Replaces</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    <VariationRows variations={shown} status={status} baseline={baseline} />
                  </Table.Tbody>
                </Table>
              </ScrollArea>
            );
          }}
        </QueryView>
      </Stack>
    </Section>
  );
}

export function VariationsPage() {
  return (
    <AutomationFrame
      title="Variations"
      breadcrumbs={[{ label: 'Automation', to: paths.automation }, { label: 'Variations' }]}
      description="The strategy settings automation replays on every captured buy, and the ones it no longer does."
      actions={
        <Button component={Link} to={paths.automationVariationNew}>
          New variation
        </Button>
      }
    >
      {(status) => <Variations status={status} />}
    </AutomationFrame>
  );
}
