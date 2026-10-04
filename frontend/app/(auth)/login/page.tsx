import { Suspense } from "react";
import { LoginForm } from "@/components/auth/LoginForm";
import { AuthLayout } from "@/components/auth/AuthLayout";
export const metadata = { title: "로그인 · Ticketing" };
export default function LoginPage() {
  return (
    <AuthLayout
      title="다시 만나 반가워요."
      description="로그인하고 기다려 온 공연을 만나세요."
    >
      <Suspense fallback={<p role="status">로그인을 준비하는 중…</p>}>
        <LoginForm />
      </Suspense>
    </AuthLayout>
  );
}
