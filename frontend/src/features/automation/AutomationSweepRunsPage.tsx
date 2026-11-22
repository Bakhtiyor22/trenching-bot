import { type AutomationStatus } from '../../api/automation';
import { Section } from '../../components/Panels';
import { daySpanText, zoneLabel } from './automationModel';
import { AutomationFrame } from './AutomationShared';
import { SweepRunsView } from './SweepRunsTable';

function SweepRuns({ status }: { status: AutomationStatus }) {
  return (
    <Section
      title="Sweep runs"
      hint={`Every variation replayed on every captured buy, newest buy first. Days are trading days (${daySpanText(status.zone, status.tradingDayStart)}), both included; times in ${zoneLabel(status.zone)}.`}
    >
      <SweepRunsView
        status={status}
        emptyText="Captured buys appear here once they are replayed."
      />
    </Section>
  );
}

export function AutomationSweepRunsPage() {
  return <AutomationFrame>{(status) => <SweepRuns status={status} />}</AutomationFrame>;
}
