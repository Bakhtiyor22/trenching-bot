import { Alert, Button, Grid, Group, Stack, Table, Text, Tooltip } from '@mantine/core';
import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  useAutomationVariation,
  type AutomationStatus,
  type AutomationVariation,
} from '../../api/automation';
import { ApiError } from '../../api/client';
import { useConfigDefaults } from '../../api/queries';
import { KeyValueTable, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { paths, VARIATION_EDIT_PARAM } from '../../paths';
import { formatSettingValue, SETTING_LABELS, type SettingField } from '../runs/configLabels';
import { formValuesFrom } from '../runs/runForm';
import {
  daySpanText,
  nextVariationLabel,
  propertiesText,
  settingsOfProperties,
  variationText,
} from './automationModel';
import {
  AutomationFrame,
  VariationLink,
  VariationStateChip,
  ZoneNote,
  ZoneTime,
} from './AutomationShared';
import { VariationEditForm, type VariationCreatedState } from './VariationEditForm';
import { SweepRunsView } from './SweepRunsTable';
import { useVariationBook } from './useVariationBook';

/** The settings in display order, as the run detail lists them. */
const SHOWN_SETTINGS = Object.keys(SETTING_LABELS) as SettingField[];

function isNotFound(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'AUTOMATION_VARIATION_NOT_FOUND';
}

/** Why Edit is not offered, or null when it is. */
function editBlock(variation: AutomationVariation, baseline: string | null): string | null {
  if (variation.state === 'ARCHIVED')
    return variation.replacedByLabel
      ? `Archived: edit its replacement, ${variation.replacedByLabel}, instead.`
      : 'Archived variations cannot be edited.';
  if (variation.label === baseline)
    return 'The agreed baseline every other variation is compared with; it is not editable.';
  return null;
}

function Settings({ variation }: { variation: AutomationVariation }) {
  const values = formValuesFrom(variation.config, null);
  const differs = new Set(settingsOfProperties(variation.differsFromAgreed));
  const changed = new Set(settingsOfProperties(variation.changedFromReplaced));
  return (
    <Table verticalSpacing="xs" aria-label={`Settings of ${variation.label}`}>
      <Table.Tbody>
        {SHOWN_SETTINGS.map((field) => (
          <Table.Tr key={field}>
            <Table.Th scope="row" fw={400} c="dimmed" w="45%">
              {SETTING_LABELS[field]}
            </Table.Th>
            <Table.Td>
              <Group gap="xs" wrap="wrap">
                <Text size="sm" ff="monospace" fw={differs.has(field) ? 600 : 400}>
                  {formatSettingValue(field, values[field])}
                </Text>
                {differs.has(field) && (
                  <Text span size="xs" c="dimmed">
                    differs from agreed
                  </Text>
                )}
                {changed.has(field) && variation.replacesLabel && (
                  <Text span size="xs" c="dimmed">
                    changed from {variation.replacesLabel}
                  </Text>
                )}
              </Group>
            </Table.Td>
          </Table.Tr>
        ))}
        <Table.Tr>
          <Table.Th scope="row" fw={400} c="dimmed">
            Strategy
          </Table.Th>
          <Table.Td>
            <Text size="sm" ff="monospace">
              {variation.config.strategyName} v{variation.config.strategyVersion}
            </Text>
          </Table.Td>
        </Table.Tr>
      </Table.Tbody>
    </Table>
  );
}

function Lineage({
  variation,
  status,
}: {
  variation: AutomationVariation;
  status: AutomationStatus;
}) {
  const none = (
    <Text span size="sm" c="dimmed">
      —
    </Text>
  );
  return (
    <KeyValueTable
      rows={[
        ['State', <VariationStateChip key="state" state={variation.state} />],
        [
          'Replayed on buys captured',
          variation.activeFrom ? (
            <Group key="from" gap={6} wrap="wrap">
              <Text span size="sm">
                from
              </Text>
              <ZoneTime iso={variation.activeFrom} zone={status.zone} withDate />
            </Group>
          ) : (
            <Text key="from" span size="sm">
              since the plan began
            </Text>
          ),
        ],
        [
          'Archived',
          variation.archivedAt ? (
            <ZoneTime key="archived" iso={variation.archivedAt} zone={status.zone} withDate />
          ) : (
            none
          ),
        ],
        [
          'Replaces',
          variation.replacesLabel ? (
            <Stack key="replaces" gap={0}>
              <VariationLink label={variation.replacesLabel} />
              {variation.changedFromReplaced.length > 0 && (
                <Text size="xs" c="dimmed">
                  changed: {propertiesText(variation.changedFromReplaced, variation.config)}
                </Text>
              )}
            </Stack>
          ) : (
            none
          ),
        ],
        [
          'Replaced by',
          variation.replacedByLabel ? (
            <VariationLink key="by" label={variation.replacedByLabel} />
          ) : (
            none
          ),
        ],
        [
          'Plan',
          <Text key="plan" span size="sm" ff="monospace">
            {variation.planId}
          </Text>,
        ],
      ]}
    />
  );
}

function Notices({
  variation,
  status,
  state,
}: {
  variation: AutomationVariation;
  status: AutomationStatus;
  /** What the edit form passed when it opened this page; null on any later visit or reload. */
  state: VariationCreatedState | null;
}) {
  if (variation.state === 'ARCHIVED')
    return (
      <Alert color="gray" variant="light" title={`${variation.label} is archived`}>
        <Text size="sm">
          Since <ZoneTime iso={variation.archivedAt} zone={status.zone} withDate /> it is no longer
          replayed on new buys. Its runs below stay as they were.
          {variation.replacedByLabel && (
            <>
              {' '}
              <VariationLink label={variation.replacedByLabel} /> replaces it.
            </>
          )}
        </Text>
      </Alert>
    );
  if (!variation.replacesLabel) return null;
  const justCreated = state?.createdFrom === variation.replacesLabel;
  return (
    <Alert
      color={justCreated ? 'teal' : 'blue'}
      variant="light"
      title={
        justCreated
          ? state.created
            ? `${variation.label} created`
            : `${variation.label} was already created by this edit`
          : `${variation.label} replaces ${variation.replacesLabel}`
      }
      role="status"
    >
      <Text size="sm">
        It runs only on buys captured from{' '}
        {variation.activeFrom ? (
          <ZoneTime iso={variation.activeFrom} zone={status.zone} withDate />
        ) : (
          'its creation'
        )}{' '}
        on{justCreated ? ', that is from now on' : ''}. Buys replayed before keep their{' '}
        <VariationLink label={variation.replacesLabel} /> runs and get none under {variation.label},
        so its first runs appear with the next captured buy.
      </Text>
    </Alert>
  );
}

/**
 * The edit form's navigation state, read once when the page opens and then cleared from the history entry: the browser
 * keeps it across a reload and back navigation, and "created, from now on" must not come back a day later.
 */
function useCreatedState(): VariationCreatedState | null {
  const location = useLocation();
  const navigate = useNavigate();
  const [state] = useState(() => location.state as VariationCreatedState | null);
  useEffect(() => {
    if (location.state != null)
      void navigate(
        { pathname: location.pathname, search: location.search, hash: location.hash },
        { replace: true, state: null },
      );
  }, [location, navigate]);
  return state;
}

function Detail({ label, status }: { label: string; status: AutomationStatus }) {
  const created = useCreatedState();
  const [params, setParams] = useSearchParams();
  const query = useAutomationVariation(label);
  const book = useVariationBook();
  const defaults = useConfigDefaults();
  const editing = params.get(VARIATION_EDIT_PARAM) === '1';
  const setEditing = (on: boolean) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (on) next.set(VARIATION_EDIT_PARAM, '1');
        else next.delete(VARIATION_EDIT_PARAM);
        return next;
      },
      { replace: true },
    );

  if (query.data === undefined && isNotFound(query.error))
    return (
      <EmptyState
        title={`No variation ${label}`}
        description="The plan has no variation under this label. Labels are never reused, so a link to one that never existed stays empty."
        action={
          <Button component={Link} to={paths.automationVariations} variant="default">
            All variations
          </Button>
        }
      />
    );

  return (
    <QueryView
      query={query}
      loadingLabel="Loading the variation…"
      errorTitle="Variation unavailable"
    >
      {(variation) => {
        // Until the list names the baseline, a variation that differs from nothing agreed is taken to be it, so the
        // baseline is never offered for editing by mistake.
        const baseline =
          book.baseline ?? (variation.differsFromAgreed.length === 0 ? variation.label : null);
        const blocked = editBlock(variation, baseline);
        const canEdit = blocked == null;
        return (
          <Stack gap="lg">
            <Notices variation={variation} status={status} state={created} />
            {editing && canEdit ? (
              defaults.data ? (
                <VariationEditForm
                  key={variation.label}
                  variation={variation}
                  limits={defaults.data.limits}
                  captureMinSol={status.captureMinSignalSol}
                  nextLabel={nextVariationLabel(book)}
                  onCancel={() => setEditing(false)}
                />
              ) : defaults.isError ? (
                <ErrorState
                  error={defaults.error}
                  title="The setting limits could not be loaded"
                  onRetry={() => void defaults.refetch()}
                />
              ) : (
                <LoadingState label="Loading the setting limits…" />
              )
            ) : (
              <Grid align="flex-start">
                <Grid.Col span={{ base: 12, lg: 7 }}>
                  <Section
                    title="Settings"
                    hint={variationText(variation)}
                    actions={
                      canEdit ? (
                        <Button size="xs" onClick={() => setEditing(true)}>
                          Edit
                        </Button>
                      ) : (
                        <Tooltip label={blocked} multiline w={260}>
                          <Button
                            size="xs"
                            variant="default"
                            data-disabled
                            aria-disabled
                            onClick={(e) => e.preventDefault()}
                          >
                            Edit
                          </Button>
                        </Tooltip>
                      )
                    }
                  >
                    <Stack gap="xs">
                      {!canEdit && (
                        <Text size="xs" c="dimmed">
                          {blocked}
                        </Text>
                      )}
                      <Settings variation={variation} />
                    </Stack>
                  </Section>
                </Grid.Col>
                <Grid.Col span={{ base: 12, lg: 5 }}>
                  <Section
                    title="Lineage"
                    hint="An edit archives a variation and creates its replacement; labels are never reused."
                  >
                    <Lineage variation={variation} status={status} />
                  </Section>
                </Grid.Col>
              </Grid>
            )}
            <Section
              title={`Runs of ${variation.label}`}
              hint={`One run per captured buy it was replayed on, newest buy first. Days are trading days (${daySpanText(status.zone, status.tradingDayStart)}), both included.`}
              actions={<ZoneNote zone={status.zone} />}
            >
              <SweepRunsView
                status={status}
                label={variation.label}
                planId={variation.planId}
                emptyText={
                  variation.state === 'ACTIVE'
                    ? `${variation.label} has no runs yet: it runs on the next captured buy.`
                    : `${variation.label} was archived before any buy was replayed under it.`
                }
              />
            </Section>
          </Stack>
        );
      }}
    </QueryView>
  );
}

export function VariationDetailPage() {
  const { label = '' } = useParams();
  return (
    <AutomationFrame
      title={`Variation ${label}`}
      breadcrumbs={[
        { label: 'Automation', to: paths.automation },
        { label: 'Variations', to: paths.automationVariations },
        { label },
      ]}
      description="Its settings, where it comes from, and every buy it was replayed on."
      actions={null}
    >
      {(status) => <Detail key={label} label={label} status={status} />}
    </AutomationFrame>
  );
}
