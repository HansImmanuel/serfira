import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { InstallmentTable } from "@/components/contracts/InstallmentTable";
import type { Installment } from "@/types/contract";

const INSTALLMENT: Installment = {
  id: "66666666-6666-4666-8666-666666666666",
  period_no: 1,
  due_date: "2026-12-01",
  principal_amount: "1000000.00",
  interest_amount: "150000.00",
  recognized_interest_amount: "150000.00",
  penalty_amount: "50000.00",
  paid_amount: "200000.00",
  settled_amount: "0.00",
  written_off_amount: "0.00",
  status: "PARTIALLY_PAID",
  outstanding: "1000000.00",
};

function cellsOf(label: string): HTMLElement[] {
  const row = screen.getByText(label).closest("tr");
  expect(row).not.toBeNull();
  return within(row as HTMLElement).getAllByRole("cell");
}

describe("InstallmentTable", () => {
  it("derives the recognized total as principal + recognized interest + effective penalty", () => {
    render(<InstallmentTable installments={[INSTALLMENT]} />);

    // 1.000.000 + 150.000 + 50.000 = 1.200.000, summed exactly without floats.
    expect(cellsOf("1")[5]).toHaveTextContent("1.200.000");
  });

  it("derives the resolved amount as paid + settled + written off, not from outstanding", () => {
    render(<InstallmentTable installments={[INSTALLMENT]} />);

    expect(cellsOf("1")[6]).toHaveTextContent("200.000");
    expect(cellsOf("1")[7]).toHaveTextContent("1.000.000");
  });

  it("shows the recognized interest, because future scheduled interest is not receivable", () => {
    render(
      <InstallmentTable
        installments={[{ ...INSTALLMENT, interest_amount: "150000.00", recognized_interest_amount: "75000.00" }]}
      />,
    );

    const cells = cellsOf("1");
    expect(cells[3]).toHaveTextContent("75.000");
    expect(cells[3]).not.toHaveTextContent("150.000");
    expect(cells[5]).toHaveTextContent("1.125.000");
  });

  it("renders a written-off row with its strikethrough status and zero resolved amount", () => {
    render(
      <InstallmentTable
        installments={[
          {
            ...INSTALLMENT,
            status: "WRITTEN_OFF",
            written_off_amount: "1150000.00",
            paid_amount: "0.00",
            outstanding: "0.00",
          },
        ]}
      />,
    );

    expect(screen.getByText("WRITTEN_OFF")).toBeInTheDocument();
    expect(cellsOf("1")[6]).toHaveTextContent("1.150.000");
  });

  it("explains that a draft has no schedule instead of showing an empty table", () => {
    render(<InstallmentTable installments={[]} />);

    expect(screen.getByText(/Belum ada jadwal angsuran/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
