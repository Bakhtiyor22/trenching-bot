import { useMemo } from 'react';
import { useAutomationVariations } from '../../api/automation';
import { variationBook, type VariationBook } from './automationModel';

/**
 * The plan's variations, active and archived, for naming labels anywhere on a page. Until they load (or when they
 * cannot be read) the book is empty: labels show without their text, and nothing else on the page waits for it.
 */
export function useVariationBook(): VariationBook & { loaded: boolean } {
  const query = useAutomationVariations(null);
  const data = query.data;
  return useMemo(() => ({ ...variationBook(data), loaded: data !== undefined }), [data]);
}
