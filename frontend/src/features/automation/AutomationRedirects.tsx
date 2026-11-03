import { Navigate, useParams } from 'react-router';
import { useAutomationItem, useAutomationSession } from '../../api/automation';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';
import { AutomationFrame } from './AutomationShared';

/** The Nights tab became Days; keep old bookmarks working. */
export function NightsRedirect() {
  return <Navigate to={paths.automationDays} replace />;
}

/**
 * `/automation/nights/:id` (a session) opens the trading day it belongs to. It renders inside the Automation frame, so
 * automation being off is explained, and a failed lookup can be retried.
 */
export function NightRedirect() {
  const { id = '' } = useParams();
  const session = useAutomationSession(id);
  return (
    <AutomationFrame actions={null}>
      {() =>
        session.data ? (
          <Navigate to={paths.automationDay(session.data.session.tradingDay)} replace />
        ) : session.isError ? (
          <ErrorState
            error={session.error}
            title="Session unavailable"
            onRetry={() => void session.refetch()}
          />
        ) : (
          <LoadingState label="Finding the session's day…" />
        )
      }
    </AutomationFrame>
  );
}

/** `/automation/items/:id` opens the buy inside its day, so a buy is always seen with its neighbours and score. */
export function ItemRedirect() {
  const { id = '' } = useParams();
  const item = useAutomationItem(id);
  const session = useAutomationSession(item.data?.item.sessionId ?? null);
  const failed = item.isError ? item : session.isError ? session : null;
  return (
    <AutomationFrame actions={null}>
      {() =>
        session.data ? (
          <Navigate to={paths.automationDay(session.data.session.tradingDay, id)} replace />
        ) : failed ? (
          <ErrorState
            error={failed.error}
            title="Buy unavailable"
            onRetry={() => void failed.refetch()}
          />
        ) : (
          <LoadingState label="Finding the buy's day…" />
        )
      }
    </AutomationFrame>
  );
}
