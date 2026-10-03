import { useState } from 'react';

/** Client-side paging for record lists that arrive in one response (bounded by ReplayLimits). */
export function useClientPaging<T>(items: T[], pageSize = 50) {
  const [page, setPage] = useState(1);
  const pages = Math.max(1, Math.ceil(items.length / pageSize));
  const current = Math.min(page, pages);
  const visible = items.slice((current - 1) * pageSize, current * pageSize);
  return { page: current, pages, setPage, visible, total: items.length };
}
