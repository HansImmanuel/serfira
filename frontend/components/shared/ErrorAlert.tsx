"use client";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ApiError } from "@/lib/api";

/** Shows the backend's envelope message verbatim, with its code as support context (FE §3.3). */
export function ErrorAlert({
  error,
  title = "Terjadi kesalahan",
  onRetry,
}: {
  error: unknown;
  title?: string;
  onRetry?: () => void;
}) {
  const apiError = error instanceof ApiError ? error : null;
  const message =
    error instanceof Error ? error.message : "Terjadi kesalahan yang tidak terduga. Silakan coba lagi.";

  return (
    <Alert variant="destructive">
      <AlertTitle>{title}</AlertTitle>
      <AlertDescription>
        <span>{message}</span>
        {apiError === null ? null : (
          <span className="ml-2 font-mono text-xs opacity-70">{apiError.code}</span>
        )}
        {onRetry === undefined ? null : (
          <Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
            Coba lagi
          </Button>
        )}
      </AlertDescription>
    </Alert>
  );
}
