import type { ReactNode } from "react";
import { cn } from "./cn";
export type StatusTone = "neutral" | "success" | "error" | "accent";
export function StatusBadge({
  tone = "neutral",
  children,
  className,
}: {
  tone?: StatusTone;
  children: ReactNode;
  className?: string;
}) {
  return (
    <span className={cn("ui-status", className)} data-tone={tone}>
      {children}
    </span>
  );
}
