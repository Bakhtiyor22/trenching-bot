import { Group, ScrollArea, Stack, Switch, Table, Text } from '@mantine/core';
import { useSearchParams } from 'react-router';
import {
  useAutomationVariations,
  type AutomationStatus,
  type AutomationVariation,
} from '../../api/automation';
import { Section } from '../../components/Panels';
import { paths } from '../../paths';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import { variationText } from './automationModel';
import {
  AutomationFrame,
  VariationLink,
  VariationStateChip,
  ZoneNote,
  ZoneTime,
} from './AutomationShared';

const ARCHIVED_PARAM = 'archived';

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
      {variations.map((v) => (
        <Table.Tr key={v.label} data-archived={v.state === 'ARCHIVED' || undefined}>
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
            <Text size="sm" c={v.state === 'ARCHIVED' ? 'dimmed' : undefined}>
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
            {v.state === 'ARCHIVED' ? (
              <Stack gap={0}>
                <ZoneTime iso={v.archivedAt} zone={status.zone} withDate />
                {v.replacedByLabel && (
                  <Text size="xs" c="dimmed">
                    replaced by <VariationLink label={v.replacedByLabel} size="xs" />
                  </Text>
                )}
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
      ))}
    </>
  );
}

function Variations({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const showArchived = params.get(ARCHIVED_PARAM) === '1';
  const active = useAutomationVariations('ACTIVE');
  const archived = useAutomationVariations('ARCHIVED', showArchived);
  const toggle = (on: boolean) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (on) next.set(ARCHIVED_PARAM, '1');
        else next.delete(ARCHIVED_PARAM);
        return next;
      },
      { replace: true },
    );
  const baseline = active.data?.items.find((v) => v.differsFromAgreed.length === 0)?.label ?? null;

  return (
    <Section
      title={`Variations${active.data ? ` · plan ${active.data.planId}` : ''}`}
      hint="Each active variation is replayed on every captured buy. Editing one archives it and creates a replacement under the next label, which runs from the next captured buy on; an archived variation keeps its runs and is never replayed again."
      actions={
        <Group gap="md" wrap="wrap">
          <ZoneNote zone={status.zone} />
          <Switch
            label="Show archived"
            checked={showArchived}
            onChange={(e) => toggle(e.currentTarget.checked)}
          />
        </Group>
      }
    >
      <QueryView
        query={active}
        loadingLabel="Loading variations…"
        errorTitle="Variations unavailable"
      >
        {(data) =>
          data.items.length === 0 ? (
            <EmptyState
              title="No active variation"
              description="The backend seeds the plan's variations at startup; none is stored for this plan yet."
            />
          ) : (
            <Stack gap="sm">
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
                    <VariationRows variations={data.items} status={status} baseline={baseline} />
                    {showArchived && archived.data && (
                      <VariationRows
                        variations={archived.data.items}
                        status={status}
                        baseline={null}
                      />
                    )}
                  </Table.Tbody>
                </Table>
              </ScrollArea>
              {showArchived && archived.data === undefined && !archived.isError && (
                <Text size="sm" c="dimmed">
                  Loading archived variations…
                </Text>
              )}
              {showArchived && archived.isError && archived.data === undefined && (
                <Text size="sm" c="red" role="alert">
                  Archived variations could not be loaded.
                </Text>
              )}
              {showArchived && archived.data?.items.length === 0 && (
                <Text size="sm" c="dimmed">
                  No variation has been archived yet.
                </Text>
              )}
            </Stack>
          )
        }
      </QueryView>
    </Section>
  );
}

export function VariationsPage() {
  return (
    <AutomationFrame
      title="Variations"
      breadcrumbs={[{ label: 'Automation', to: paths.automation }, { label: 'Variations' }]}
      description="The strategy settings automation replays on every captured buy, and the ones it no longer does."
    >
      {(status) => <Variations status={status} />}
    </AutomationFrame>
  );
}
