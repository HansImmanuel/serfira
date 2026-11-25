"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { Controller, useForm } from "react-hook-form";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useIdempotencyKey } from "@/hooks/useIdempotencyKey";
import { useCreatePayment } from "@/hooks/usePayment";
import { isSpentKeyError } from "@/lib/idempotency";
import {
  PAYMENT_CHANNEL_OPTIONS,
  emptyPaymentValues,
  paymentSchema,
  toPaymentPayload,
  type PaymentValues,
} from "@/lib/schemas/payment";
import type { Payment } from "@/types/payment";

function FieldError({ message }: { message: string | undefined }) {
  return message === undefined ? null : (
    <p className="text-xs text-destructive" role="alert">
      {message}
    </p>
  );
}

/**
 * Record a payment (FE §2.6): amount and channel only. The payment instant is the server clock
 * (TS §2.0) and allocation is the server's job (ADR-009), so neither is an input here.
 *
 * <p>The `Idempotency-Key` is fixed for the life of this mount (FE §1.4): a retry after a timeout or
 * a double click replays the original payment. A fresh key comes only from remounting the form,
 * which the parent does through `onRestart`.
 */
export function RecordPaymentForm({
  contractId,
  onPosted,
  onRestart,
}: {
  contractId: string;
  onPosted: (payment: Payment) => void;
  onRestart: () => void;
}) {
  const idempotencyKey = useIdempotencyKey();
  const mutation = useCreatePayment(contractId);
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<PaymentValues>({
    resolver: zodResolver(paymentSchema),
    defaultValues: emptyPaymentValues,
  });

  // A spent key (ADR-017): the same key can no longer be used for this request, so restart is explicit.
  const spentKeyError = mutation.isError && isSpentKeyError(mutation.error) ? mutation.error : null;
  const keySpent = spentKeyError !== null;

  async function onSubmit(values: PaymentValues) {
    try {
      const payment = await mutation.mutateAsync({
        payload: toPaymentPayload(contractId, values),
        idempotencyKey,
      });
      onPosted(payment);
    } catch {
      // The mutation's error is rendered below; the key stays the same for a retry.
    }
  }

  return (
    <form className="max-w-md space-y-4" onSubmit={handleSubmit(onSubmit)} noValidate>
      {keySpent ? (
        <Alert variant="destructive">
          <AlertTitle>Pembayaran tidak diproses</AlertTitle>
          <AlertDescription>
            <span>
              {spentKeyError?.code === "IDEMPOTENCY_KEY_EXPIRED"
                ? "Kunci formulir ini sudah tidak berlaku, dan pembayaran sebelumnya mungkin sudah tercatat. "
                : "Formulir ini sudah dipakai untuk pembayaran dengan isian berbeda. "}
              Periksa rekening koran kontrak lebih dulu agar pembayaran tidak tercatat dua kali, lalu mulai form baru.
            </span>
            <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRestart}>
              Mulai form baru
            </Button>
          </AlertDescription>
        </Alert>
      ) : mutation.isError ? (
        <ErrorAlert error={mutation.error} title="Pembayaran tidak dapat dicatat" />
      ) : null}

      <div className="space-y-1.5">
        <Label htmlFor="amount">Jumlah Bayar</Label>
        <Input
          id="amount"
          inputMode="decimal"
          autoComplete="off"
          {...register("amount")}
          aria-invalid={errors.amount !== undefined}
        />
        <FieldError message={errors.amount?.message} />
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="channel">Channel</Label>
        <Controller
          control={control}
          name="channel"
          render={({ field }) => (
            <Select value={field.value} onValueChange={field.onChange}>
              <SelectTrigger id="channel" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PAYMENT_CHANNEL_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        />
        <FieldError message={errors.channel?.message} />
      </div>

      <p className="text-xs text-muted-foreground">
        Tanggal bayar dicatat otomatis oleh server saat pembayaran diterima.
      </p>

      <Button type="submit" disabled={keySpent || isSubmitting || mutation.isPending}>
        {mutation.isPending ? "Menyimpan…" : "Simpan Pembayaran"}
      </Button>
    </form>
  );
}
