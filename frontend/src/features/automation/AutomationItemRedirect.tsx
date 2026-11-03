import { Navigate, useParams } from 'react-router';
import { useAutomationItem } from '../../api/automation';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { paths } from '../../paths';

/** `/automation/items/:id` opens the buy inside its night, so a buy is always seen with its neighbours and score. */
export function AutomationItemRedirect() {
  const { id = '' } = useParams();
  const item = useAutomationItem(id);
  if (item.isPending) return <LoadingState label="Finding the buy's night…" />;
  if (item.isError) return <ErrorState error={item.error} title="Buy unavailable" />;
  return <Navigate to={paths.automationNight(item.data.item.sessionId, id)} replace />;
}
