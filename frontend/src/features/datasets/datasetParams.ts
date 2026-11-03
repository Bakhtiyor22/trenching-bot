/** URL state of the dataset pages, so a shared link shows the same list page and pair. */
export const PAGE_PARAM = 'page';
export const PAIR_PARAM = 'pair';

/** The zero-based list page in `?page`; anything that is not a whole number from 0 reads as the first page. */
export function pageFromParam(value: string | null): number {
  const page = Number(value ?? '0');
  return Number.isSafeInteger(page) && page >= 0 ? page : 0;
}

/** The pair in `?pair` when the dataset has it, else the dataset's first pair; null for a dataset without pairs. */
export function pairFromParam(value: string | null, pairs: string[]): string | null {
  return value !== null && pairs.includes(value) ? value : (pairs[0] ?? null);
}
