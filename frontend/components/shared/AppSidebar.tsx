"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { FileText } from "lucide-react";

import { cn } from "@/lib/utils";

const NAV_ITEMS = [{ href: "/contracts", label: "Kontrak", icon: FileText }];

export function AppSidebar() {
  const pathname = usePathname();

  return (
    <aside className="hidden w-56 flex-col border-r bg-sidebar px-3 py-4 md:flex">
      <div className="px-2 pb-4 text-lg font-semibold tracking-tight">Serfira</div>
      <nav className="flex flex-col gap-1" aria-label="Navigasi utama">
        {NAV_ITEMS.map((item) => {
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
          const Icon = item.icon;
          return (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active ? "page" : undefined}
              className={cn(
                "flex items-center gap-2 rounded-md px-2 py-1.5 text-sm",
                active ? "bg-sidebar-accent text-sidebar-accent-foreground font-medium" : "hover:bg-sidebar-accent/60",
              )}
            >
              <Icon className="size-4" aria-hidden="true" />
              {item.label}
            </Link>
          );
        })}
      </nav>
      <div className="mt-auto px-2 text-xs text-muted-foreground">Fase 1–2 · operasional</div>
    </aside>
  );
}
