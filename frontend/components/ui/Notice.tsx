import type { ReactNode } from "react";
export function Notice({
  title,
  children,
  tone = "info",
  action,
}: {
  title: string;
  children?: ReactNode;
  tone?: "info" | "error" | "success";
  action?: ReactNode;
}) {
  return (
    <div
      className={`notice ${tone === "info" ? "" : `notice-${tone}`}`}
      role={tone === "error" ? "alert" : undefined}
    >
      <p className="font-medium">{title}</p>
      {children && (
        <div className="mt-2 text-sm leading-relaxed">{children}</div>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
