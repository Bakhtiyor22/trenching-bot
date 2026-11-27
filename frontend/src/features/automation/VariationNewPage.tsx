import { Select } from '@mantine/core';
import { useNavigate, useSearchParams } from 'react-router';
import { type AutomationStatus } from '../../api/automation';
import { useConfigDefaults } from '../../api/queries';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import { nameOf, newVariationName } from './automationModel';
import { AutomationFrame } from './AutomationShared';
import { useVariationBook } from './useVariationBook';
import { VariationEditForm } from './VariationEditForm';

/** The URL key of the variation whose settings the new one starts from; the agreed baseline by default. */
const FROM_PARAM = 'from';

/**
 * A new variation under the next base number: the edit form, prefilled with the agreed settings or a copy of any
 * variation's, archived ones included. It replaces nothing; on success the new variation's page opens.
 */
function NewVariation({ status }: { status: AutomationStatus }) {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const book = useVariationBook();
  const defaults = useConfigDefaults();
  if (!book.loaded || defaults.data === undefined) {
    if (defaults.isError)
      return (
        <ErrorState
          error={defaults.error}
          title="The setting limits could not be loaded"
          onRetry={() => void defaults.refetch()}
        />
      );
    return <LoadingState label="Loading the variations and setting limits…" />;
  }
  const requested = params.get(FROM_PARAM);
  const source =
    (requested ? book.find(requested) : undefined) ??
    (book.baseline ? book.find(book.baseline) : undefined) ??
    book.variations[0];
  if (!source)
    return (
      <ErrorState
        error={new Error('The plan has no variation to copy settings from.')}
        title="No variation to start from"
      />
    );
  const picker = (
    <Select
      label="Copy settings from"
      description="The agreed settings (the baseline) by default; any variation's settings, archived ones included."
      data={book.variations.map((v) => ({
        value: v.label,
        label: `${nameOf(v)}${v.label === book.baseline ? ' (agreed)' : ''}${v.state === 'ARCHIVED' ? ' (archived)' : ''}`,
      }))}
      value={source.label}
      onChange={(value) =>
        value &&
        setParams(
          (prev) => {
            const next = new URLSearchParams(prev);
            next.set(FROM_PARAM, value);
            return next;
          },
          { replace: true },
        )
      }
      allowDeselect={false}
      searchable
      w={{ base: '100%', sm: 320 }}
    />
  );
  return (
    <VariationEditForm
      // A new source starts the form over from its settings.
      key={source.label}
      mode="create"
      variation={source}
      limits={defaults.data.limits}
      captureMinSol={status.captureMinSignalSol}
      savesAs={newVariationName(book)}
      sourcePicker={picker}
      onCancel={() => void navigate(paths.automationVariations)}
    />
  );
}

export function VariationNewPage() {
  return (
    <AutomationFrame
      title="New variation"
      breadcrumbs={[
        { label: 'Automation', to: paths.automation },
        { label: 'Variations', to: paths.automationVariations },
        { label: 'New' },
      ]}
      description="A variation of its own, under the next base number, replayed on every buy captured from now on."
      actions={null}
    >
      {(status) => <NewVariation status={status} />}
    </AutomationFrame>
  );
}
