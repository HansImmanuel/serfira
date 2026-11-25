"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Controller, useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useCreateContract } from "@/hooks/useContract";
import { useIdempotencyKey } from "@/hooks/useIdempotencyKey";
import { formatMoney } from "@/lib/format";
import { normalizeMoney, subtractMoney } from "@/lib/decimal";
import {
  ASSET_TYPE_OPTIONS,
  INTEREST_SCHEME_OPTIONS,
  createContractSchema,
  emptyCreateContractValues,
  toCreateContractPayload,
  type CreateContractValues,
} from "@/lib/schemas/createContract";

function FieldError({ message }: { message: string | undefined }) {
  return message === undefined ? null : (
    <p className="text-xs text-destructive" role="alert">
      {message}
    </p>
  );
}

/**
 * Draft a contract (FE §2.3). "Tanggal Mulai" is the planned start date; activation pins the
 * effective date (ADR-006). The `Idempotency-Key` is generated once per mount, so a retry replays
 * the original create instead of producing a second contract (ADR-007).
 */
export function CreateContractForm() {
  const router = useRouter();
  const idempotencyKey = useIdempotencyKey();
  const mutation = useCreateContract();
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<CreateContractValues>({
    resolver: zodResolver(createContractSchema),
    defaultValues: emptyCreateContractValues,
  });

  const assetPrice = useWatch({ control, name: "asset_price" });
  const downPayment = useWatch({ control, name: "down_payment" });
  const principalPreview =
    normalizeMoney(assetPrice) !== null && normalizeMoney(downPayment) !== null
      ? formatMoney(subtractMoney(assetPrice, downPayment))
      : "–";

  async function onSubmit(values: CreateContractValues) {
    try {
      const created = await mutation.mutateAsync({
        payload: toCreateContractPayload(values),
        idempotencyKey,
      });
      toast.success(`Kontrak ${created.contract_no} dibuat sebagai DRAFT.`);
      router.push(`/contracts/${created.id}`);
    } catch {
      // The mutation's error is rendered below; nothing else to do here.
    }
  }

  return (
    <form className="space-y-4" onSubmit={handleSubmit(onSubmit)} noValidate>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Buat Kontrak</h1>
          <p className="text-sm text-muted-foreground">
            Kontrak disimpan sebagai DRAFT; jadwal angsuran dibuat saat aktivasi.
          </p>
        </div>
        <div className="flex gap-2">
          <Button asChild variant="outline">
            <Link href="/contracts">Batal</Link>
          </Button>
          <Button type="submit" disabled={isSubmitting || mutation.isPending}>
            {mutation.isPending ? "Menyimpan…" : "Simpan sebagai DRAFT"}
          </Button>
        </div>
      </div>

      {mutation.isError ? <ErrorAlert error={mutation.error} title="Kontrak tidak dapat dibuat" /> : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Data Nasabah</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <div className="space-y-1.5 md:col-span-2">
            <Label htmlFor="full_name">Nama Nasabah</Label>
            <Input id="full_name" {...register("full_name")} aria-invalid={errors.full_name !== undefined} />
            <FieldError message={errors.full_name?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="nik">NIK</Label>
            <Input id="nik" inputMode="numeric" {...register("nik")} aria-invalid={errors.nik !== undefined} />
            <FieldError message={errors.nik?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="phone">No. Telepon</Label>
            <Input id="phone" inputMode="tel" {...register("phone")} aria-invalid={errors.phone !== undefined} />
            <FieldError message={errors.phone?.message} />
          </div>
          <div className="space-y-1.5 md:col-span-2">
            <Label htmlFor="address">Alamat</Label>
            <Textarea id="address" rows={2} {...register("address")} aria-invalid={errors.address !== undefined} />
            <FieldError message={errors.address?.message} />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Aset</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="asset_type">Tipe Aset</Label>
            <Controller
              control={control}
              name="asset_type"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger id="asset_type" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {ASSET_TYPE_OPTIONS.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
            <FieldError message={errors.asset_type?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="brand">Merek</Label>
            <Input id="brand" {...register("brand")} aria-invalid={errors.brand !== undefined} />
            <FieldError message={errors.brand?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="model">Model</Label>
            <Input id="model" {...register("model")} aria-invalid={errors.model !== undefined} />
            <FieldError message={errors.model?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="serial_no">No. Seri (opsional)</Label>
            <Input id="serial_no" {...register("serial_no")} />
            <FieldError message={errors.serial_no?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="plate_no">No. Plat (opsional)</Label>
            <Input id="plate_no" {...register("plate_no")} />
            <FieldError message={errors.plate_no?.message} />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Pembiayaan</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 md:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="asset_price">Harga Aset (Rp)</Label>
            <Input
              id="asset_price"
              inputMode="decimal"
              placeholder="20000000"
              {...register("asset_price")}
              aria-invalid={errors.asset_price !== undefined}
            />
            <FieldError message={errors.asset_price?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="down_payment">Uang Muka (Rp)</Label>
            <Input
              id="down_payment"
              inputMode="decimal"
              placeholder="5000000"
              {...register("down_payment")}
              aria-invalid={errors.down_payment !== undefined}
            />
            <FieldError message={errors.down_payment?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="principal_preview">Plafon (otomatis)</Label>
            <Input id="principal_preview" value={principalPreview} readOnly disabled />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="tenor_months">Tenor (bulan)</Label>
            <Input
              id="tenor_months"
              inputMode="numeric"
              placeholder="12"
              {...register("tenor_months")}
              aria-invalid={errors.tenor_months !== undefined}
            />
            <FieldError message={errors.tenor_months?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="interest_scheme">Skema Bunga</Label>
            <Controller
              control={control}
              name="interest_scheme"
              render={({ field }) => (
                <Select value={field.value} onValueChange={field.onChange}>
                  <SelectTrigger id="interest_scheme" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {INTEREST_SCHEME_OPTIONS.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            />
            <FieldError message={errors.interest_scheme?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="interest_rate_percent">Suku Bunga (% per bulan)</Label>
            <Input
              id="interest_rate_percent"
              inputMode="decimal"
              placeholder="1.5"
              {...register("interest_rate_percent")}
              aria-invalid={errors.interest_rate_percent !== undefined}
            />
            <FieldError message={errors.interest_rate_percent?.message} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="planned_start_date">Tanggal Mulai</Label>
            <Input
              id="planned_start_date"
              type="date"
              {...register("planned_start_date")}
              aria-invalid={errors.planned_start_date !== undefined}
            />
            <FieldError message={errors.planned_start_date?.message} />
            <p className="text-xs text-muted-foreground">
              Tanggal rencana; aktivasi memakai tanggal ini sebagai tanggal efektif.
            </p>
          </div>
        </CardContent>
      </Card>
    </form>
  );
}
