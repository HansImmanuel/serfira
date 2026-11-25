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

/**
 * Roles allowed to read customer credit and the ledger statement.
 *
 * <p>`ResourceServerSecurityConfiguration` restricts `GET /contracts/{id}/credit` and
 * `GET /contracts/{id}/statement` to these two roles; `MANAJEMEN` reads reports and contract detail
 * only. These helpers keep the UI from offering a tab or link that would answer 403 — the server
 * still enforces the rule.
 */
const CREDIT_AND_STATEMENT_ROLES = ["ADMIN_OPERASIONAL", "FINANCE"] as const;

function hasAnyRole(roles: string[], allowed: readonly string[]): boolean {
  return roles.some((role) => allowed.includes(role));
}

export function useCanReadCredit(): boolean {
  return hasAnyRole(useSession().roles, CREDIT_AND_STATEMENT_ROLES);
}

export function useCanReadStatement(): boolean {
  return hasAnyRole(useSession().roles, CREDIT_AND_STATEMENT_ROLES);
}
