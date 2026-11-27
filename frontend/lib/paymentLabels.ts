import { PAYMENT_CHANNEL_OPTIONS } from "@/lib/schemas/payment";
import type { AllocationType, PaymentChannel } from "@/types/payment";

const ALLOCATION_LABELS: Record<AllocationType, string> = {
  PENALTY: "Denda",
  INTEREST: "Bunga",
  PRINCIPAL: "Pokok",
  EXCESS: "Kelebihan",
};

export function allocationLabel(type: AllocationType): string {
  return ALLOCATION_LABELS[type];
}

export function channelLabel(channel: PaymentChannel): string {
  return PAYMENT_CHANNEL_OPTIONS.find((option) => option.value === channel)?.label ?? channel;
}
