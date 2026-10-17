import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError, BAD_RESPONSE } from '../../api/client';
import { capturesApi, type CaptureSession } from '../../api/captures';

export const CAPTURE_POLL_MS = 2000;
export const captureKeys = {
  all: ['captures'] as const,
  session: (id: string) => ['captures', id] as const,
  cases: (id: string) => ['captures', id, 'cases'] as const,
  config: ['capture-config'] as const,
};
export const isCaptureOperationActive = (state?: string | null) =>
  state === 'RUNNING' || state === 'WAITING_RETRY';
export const isCaptureOperationOpen = (state?: string | null) =>
  isCaptureOperationActive(state) || state === 'PAUSED';
export const capturePollInterval = (session?: CaptureSession) =>
  isCaptureOperationActive(session?.operation?.state) ||
  session?.summary?.status === 'RUNNING' ||
  session?.summary?.status === 'WAITING_RETRY'
    ? CAPTURE_POLL_MS
    : false;
export function useCaptureConfig() {
  return useQuery({
    queryKey: captureKeys.config,
    queryFn: ({ signal }) => capturesApi.config(signal),
  });
}
export function useCapture(id: string) {
  return useQuery({
    queryKey: captureKeys.session(id),
    queryFn: ({ signal }) => capturesApi.session(id, signal),
    refetchInterval: (query) => capturePollInterval(query.state.data),
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
}
export function useCaptureCases(id: string, mainOperationState?: string) {
  return useQuery({
    queryKey: captureKeys.cases(id),
    queryFn: ({ signal }) => capturesApi.cases(id, signal),
    refetchInterval: (query) =>
      isCaptureOperationActive(mainOperationState) ||
      query.state.data?.items.some(
        (item) =>
          isCaptureOperationActive(item.readiness) || isCaptureOperationActive(item.operationState),
      )
        ? CAPTURE_POLL_MS
        : false,
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
}
export function useCaptures(page: number, sort = 'createdAt,desc', status?: string | null) {
  return useQuery({
    queryKey: [...captureKeys.all, 'list', page, sort, status],
    queryFn: ({ signal }) => capturesApi.list(page, signal, sort, status),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.items.some((item) => capturePollInterval(item)) ? CAPTURE_POLL_MS : false,
    refetchIntervalInBackground: false,
  });
}

/** Deletes a capture session and refreshes the capture lists; its detail cache is dropped. */
export function useDeleteCapture() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => capturesApi.remove(id),
    onSuccess: async (_, id) => {
      client.removeQueries({ queryKey: captureKeys.session(id) });
      await client.invalidateQueries({ queryKey: captureKeys.all });
    },
  });
}

/** An uncertain POST is replayed with its original command and id, including after a page reload. */
export function useCaptureCommand<T extends { requestId: string }, R>(
  key: string,
  send: (command: T) => Promise<R>,
  onSuccess: (result: R) => void | Promise<void>,
) {
  const storageKey = `capture-request:${key}`;
  const [pending, setPending] = useState<T | null>(() => {
    const saved = sessionStorage.getItem(storageKey);
    if (!saved) return null;
    try {
      return JSON.parse(saved) as T;
    } catch {
      sessionStorage.removeItem(storageKey);
      return null;
    }
  });
  const mutation = useMutation({
    mutationFn: send,
    onSuccess: async (result) => {
      sessionStorage.removeItem(storageKey);
      setPending(null);
      await onSuccess(result);
    },
    onError: (error) => {
      if (
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.code !== BAD_RESPONSE
      ) {
        sessionStorage.removeItem(storageKey);
        setPending(null);
      }
    },
  });
  return {
    ...mutation,
    pendingCommand: pending,
    submit: (command: Omit<T, 'requestId'>) => {
      if (pending || mutation.isPending) return;
      const request = { ...command, requestId: crypto.randomUUID() } as T;
      sessionStorage.setItem(storageKey, JSON.stringify(request));
      setPending(request);
      mutation.mutate(request);
    },
    retry: () => {
      if (pending && !mutation.isPending) mutation.mutate(pending);
    },
  };
}
