"use client";

import { useParams } from "next/navigation";

import { StatementView } from "@/components/contracts/StatementView";

export default function ContractStatementPage() {
  const params = useParams<{ id: string }>();
  return <StatementView contractId={params.id} />;
}
