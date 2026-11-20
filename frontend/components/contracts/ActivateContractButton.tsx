"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Play } from "lucide-react";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { useActivateContract } from "@/hooks/useContract";
import { formatDate } from "@/lib/format";

/**
 * Activates a DRAFT contract (FE §2.4).
 *
 * <p>The form deliberately has no date input: the effective start date is the contract's
 * `planned_start_date` (ADR-006), so activation cannot silently move the schedule. The request
 * carries no `Idempotency-Key` because the action is idempotent by state (ADR-007).
 */
export function ActivateContractButton({
  contractId,
  plannedStartDate,
}: {
  contractId: string;
  plannedStartDate: string;
}) {
  const [open, setOpen] = useState(false);
  const mutation = useActivateContract(contractId);

  async function handleActivate() {
    try {
      await mutation.mutateAsync();
      setOpen(false);
      toast.success("Kontrak diaktifkan dan jadwal angsuran dibuat.");
    } catch {
      // The mutation error renders inside the dialog; keep it open so the operator can retry.
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm">
          <Play className="size-4" aria-hidden="true" />
          Aktifkan
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Aktifkan kontrak?</DialogTitle>
          <DialogDescription>
            Jadwal angsuran dibuat dengan tanggal efektif {formatDate(plannedStartDate)}. Setelah aktif,
            kontrak tidak dapat dikembalikan ke DRAFT.
          </DialogDescription>
        </DialogHeader>
        {mutation.isError ? <ErrorAlert error={mutation.error} title="Aktivasi gagal" /> : null}
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)} disabled={mutation.isPending}>
            Batal
          </Button>
          <Button onClick={handleActivate} disabled={mutation.isPending}>
            {mutation.isPending ? "Mengaktifkan…" : "Aktifkan kontrak"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
