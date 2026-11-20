"use client";

import { CreateContractForm } from "@/components/contracts/CreateContractForm";
import { RequireWrite } from "@/components/shared/RequireWrite";

export default function NewContractPage() {
  return (
    <RequireWrite>
      <CreateContractForm />
    </RequireWrite>
  );
}
