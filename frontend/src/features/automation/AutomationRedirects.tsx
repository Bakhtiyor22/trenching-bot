import { Navigate, useParams } from 'react-router';
import { useAutomationItem, useAutomationSession } from '../../api/automation';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';

/** The Nights tab became Days; keep old bookmarks working. */
export function NightsRedirect() {
  return <Navigate to={paths.automationDays} replace />;
}

/** `/automation/nights/:id` (a session) opens the trading day it belongs to. */
export function NightRedirect() {
  const { id = '' } = useParams();
  const session = useAutomationSession(id);
  if (session.isPending) return <LoadingState label="Finding the session's day…" />;
  if (session.isError) return <ErrorState error={session.error} title="Session unavailable" />;
  return <Navigate to={paths.automationDay(session.data.session.tradingDay)} replace />;
}

/** `/automation/items/:id` opens the buy inside its day, so a buy is always seen with its neighbours and score. */
export function ItemRedirect() {
  const { id = '' } = useParams();
  const item = useAutomationItem(id);
  const session = useAutomationSession(item.data?.item.sessionId ?? null);
  if (item.isError) return <ErrorState error={item.error} title="Buy unavailable" />;
  if (session.isError) return <ErrorState error={session.error} title="Buy unavailable" />;
  if (!session.data) return <LoadingState label="Finding the buy's day…" />;
  return <Navigate to={paths.automationDay(session.data.session.tradingDay, id)} replace />;
}
