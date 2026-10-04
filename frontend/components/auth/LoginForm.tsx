/**
 * 이 파일의 책임: 로그인 폼 (클라이언트 컴포넌트).
 *
 *  - react-hook-form + zodResolver 로 클라 단 검증.
 *  - 성공 시 AuthProvider.login → next 쿼리 파라미터가 있으면 그곳으로,
 *    없으면 홈으로.
 *  - backend 에러 코드별 한국어 메시지 매핑:
 *      INVALID_CREDENTIALS  → 이메일 또는 비밀번호가 올바르지 않습니다
 *      ACCOUNT_LOCKED       → 계정 잠금 (해제 시각 표시)
 *      BACKEND_UNREACHABLE  → 서버 연결 실패
 *      그 외                 → fallback
 */

"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { safeReturnPath } from "@/lib/auth/returnPath";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { useAuth } from "@/lib/auth/useAuth";
import { ApiRequestError } from "@/lib/api/client";
import { LoginRequestSchema, type LoginRequest } from "@/lib/api/schemas";

function mapLoginError(e: unknown): string {
  if (e instanceof ApiRequestError) {
    const code = e.payload?.code;
    switch (code) {
      case "INVALID_CREDENTIALS":
        return "이메일 또는 비밀번호가 올바르지 않습니다.";
      case "ACCOUNT_LOCKED": {
        const unlock = e.payload?.unlockAt;
        if (unlock) {
          try {
            const t = new Date(unlock).toLocaleString("ko-KR");
            return `계정이 잠겼습니다. ${t} 이후 다시 시도해 주세요.`;
          } catch {
            // fall through
          }
        }
        return "계정이 잠겼습니다. 잠시 후 다시 시도해 주세요.";
      }
      case "VALIDATION_ERROR":
        return "입력값을 다시 확인해 주세요.";
      case "BACKEND_UNREACHABLE":
        return "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
      default:
        return e.message || "로그인에 실패했습니다.";
    }
  }
  return e instanceof Error ? e.message : "로그인에 실패했습니다.";
}

export function LoginForm() {
  const { login } = useAuth();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginRequest>({
    resolver: zodResolver(LoginRequestSchema),
    defaultValues: { email: "", password: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      await login(values.email, values.password);
      const next = searchParams.get("next");
      router.push(safeReturnPath(next));
    } catch (e) {
      setServerError(mapLoginError(e));
    }
  });

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <Field label="이메일" error={errors.email?.message}>
        <Input
          id="email"
          type="email"
          autoComplete="email"
          {...register("email")}
        />
      </Field>
      <Field label="비밀번호" error={errors.password?.message}>
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          {...register("password")}
        />
      </Field>
      {serverError ? (
        <p
          role="alert"
          className="text-sm text-[color:var(--color-destructive)]"
        >
          {serverError}
        </p>
      ) : null}
      <Button
        type="submit"
        loading={isSubmitting}
        loadingLabel="로그인 중…"
        className="w-full"
      >
        로그인
      </Button>
      <p className="text-sm text-[color:var(--color-muted-foreground)]">
        계정이 없으신가요?{" "}
        <Link
          href={`/signup?next=${encodeURIComponent(safeReturnPath(searchParams.get("next")))}`}
          className="underline"
        >
          회원가입
        </Link>
      </p>
    </form>
  );
}
