import { Suspense } from "react";
import { SignupForm } from "@/components/auth/SignupForm";
import { AuthLayout } from "@/components/auth/AuthLayout";
export const metadata = { title: "회원가입 · Ticketing" };
export default function SignupPage() {
  return (
    <AuthLayout
      title="다음 장면을 함께해요."
      description="공연을 발견하고 나만의 티켓을 모아보세요."
    >
      <Suspense fallback={<p role="status">가입 화면을 준비하는 중…</p>}>
        <SignupForm />
      </Suspense>
    </AuthLayout>
  );
}
