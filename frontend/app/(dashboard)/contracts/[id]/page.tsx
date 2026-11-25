"use client";

import { useParams } from "next/navigation";

import { ContractDetailView } from "@/components/contracts/ContractDetailView";

export default function ContractDetailPage() {
  const params = useParams<{ id: string }>();
  return <ContractDetailView contractId={params.id} />;
}
