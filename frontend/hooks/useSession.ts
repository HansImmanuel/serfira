"use client";

import { useSyncExternalStore } from "react";

import { getSession, subscribeSession, type SessionState } from "@/lib/session";

const SERVER_SNAPSHOT: SessionState = Object.freeze({ authenticated: false, username: null, roles: [] });

function getServerSnapshot(): SessionState {
  return SERVER_SNAPSHOT;
}

/** Reactive view of the in-memory session for components. */
export function useSession(): SessionState {
  return useSyncExternalStore(subscribeSession, getSession, getServerSnapshot);
}

/**
 * Whether the UI may offer write actions (FE §3.5). UX only: the backend's matcher table decides,
 * and a forced call still answers 403 (ADR-015).
 */
export function useCanWrite(): boolean {
  return useSession().roles.includes("ADMIN_OPERASIONAL");
}
