"use client";

import Link from "next/link";
import { ShieldAlert } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * Shown when the signed-in role has no access to a screen (FE §3.5).
 *
 * <p>This is UX, not authorization: the backend already answers 403 for the underlying call. The
 * screen therefore renders no data and issues no request — a bookmarked URL must not reveal that
 * the resource exists.
 */
export function AccessDenied({
  title = "Akses ditolak",
  message,
  backHref,
  backLabel,
}: {
  title?: string;
  message: string;
  backHref?: string;
  backLabel?: string;
}) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center gap-2">
        <ShieldAlert className="size-5 text-muted-foreground" aria-hidden="true" />
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm text-muted-foreground">
        <p>{message}</p>
        {backHref === undefined || backLabel === undefined ? null : (
          <Button asChild variant="outline" size="sm">
            <Link href={backHref}>{backLabel}</Link>
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
