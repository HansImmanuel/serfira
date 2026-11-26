import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const pathname = vi.hoisted(() => ({ current: "/contracts" }));

vi.mock("next/navigation", () => ({
  usePathname: () => pathname.current,
}));

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import { AppSidebar } from "@/components/shared/AppSidebar";
import { clearSession, setSession } from "@/lib/session";

import { accessTokenWithRoles } from "../../test/utils";

function signInAs(roles: string[]) {
  act(() => {
    setSession({ access_token: accessTokenWithRoles(roles), refresh_token: "refresh-1" }, "operator");
  });
}

describe("AppSidebar", () => {
  afterEach(() => {
    act(() => clearSession());
    pathname.current = "/contracts";
  });

  it.each(["ADMIN_OPERASIONAL", "FINANCE", "MANAJEMEN"])("shows both report pages to %s", (role) => {
    signInAs([role]);
    render(<AppSidebar />);

    expect(screen.getByText("Laporan")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Aging/ })).toHaveAttribute("href", "/reports/aging");
    expect(screen.getByRole("link", { name: /Outstanding/ })).toHaveAttribute("href", "/reports/outstanding");
    expect(screen.getByRole("link", { name: /Kontrak/ })).toHaveAttribute("href", "/contracts");
  });

  it("hides the report pages from a session without a reporting role", () => {
    signInAs([]);
    render(<AppSidebar />);

    expect(screen.queryByText("Laporan")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Aging/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Outstanding/ })).not.toBeInTheDocument();
  });

  it("marks the current report page", () => {
    pathname.current = "/reports/aging";
    signInAs(["FINANCE"]);
    render(<AppSidebar />);

    expect(screen.getByRole("link", { name: /Aging/ })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: /Outstanding/ })).not.toHaveAttribute("aria-current");
  });
});
