import type { ReactNode } from "react";

export function AuthLayout({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <div className="hybrid-container page-space">
      <div className="auth-layout">
        <aside className="auth-art" aria-hidden="true">
          <p className="text-xs tracking-widest">TICKETING · YOUR NEXT NIGHT</p>
          <div>
            <p className="display italic text-[clamp(64px,6vw,100px)]">
              Be there.
              <br />
              Feel it all.
            </p>
            <p className="mt-8 text-lg">
              마음에 남을 순간,
              <br />
              그곳에 당신의 자리를.
            </p>
          </div>
          <div className="auth-art-lines" />
        </aside>
        <section className="auth-form space-y-8">
          <header className="space-y-3">
            <p className="eyebrow">WELCOME TO TICKETING</p>
            <h1 className="page-title">{title}</h1>
            <p className="muted">{description}</p>
          </header>
          {children}
        </section>
      </div>
    </div>
  );
}
