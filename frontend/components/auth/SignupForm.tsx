/**
 * 이 파일의 책임: 회원가입 폼 (클라이언트 컴포넌트).
 *
 *  - react-hook-form + zodResolver. 비밀번호 정책은 schemas.ts 의
 *    SignupRequestSchema 에서 중앙 관리.
 *  - 성공 시 안내 화면 → 로그인 페이지로 유도.
 *  - 에러 코드 매핑:
 *      EMAIL_CONFLICT       → 이미 가입된 이메일
 *      VALIDATION_ERROR     → 필드별 메시지 적용 (가능하면 backend 의 fields 우선)
 *      BACKEND_UNREACHABLE  → 서버 연결 실패
 */

"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { safeReturnPath } from "@/lib/auth/returnPath";
import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import { Input } from "@/components/ui/Input";
import { signup } from "@/lib/api/auth";
import { ApiRequestError } from "@/lib/api/client";
import { SignupRequestSchema, type SignupRequest } from "@/lib/api/schemas";

function mapSignupError(e: unknown): {
  general?: string;
  fields?: Partial<Record<keyof SignupRequest, string>>;
} {
  if (e instanceof ApiRequestError) {
    const code = e.payload?.code;
    if (code === "EMAIL_CONFLICT") {
      return { fields: { email: "이미 가입된 이메일입니다." } };
    }
    if (code === "VALIDATION_ERROR" && e.payload?.fields) {
      const f = e.payload.fields;
      return {
        fields: {
          email: f.email,
          password: f.password,
          name: f.name,
        },
      };
    }
    if (code === "BACKEND_UNREACHABLE") {
      return {
        general: "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
      };
    }
    return { general: e.message || "회원가입에 실패했습니다." };
  }
  return {
    general: e instanceof Error ? e.message : "회원가입에 실패했습니다.",
  };
}

export function SignupForm() {
  const params = useSearchParams();
  const loginHref = `/login?next=${encodeURIComponent(safeReturnPath(params.get("next")))}`;
  const [done, setDone] = useState(false);
  const [generalError, setGeneralError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SignupRequest>({
    resolver: zodResolver(SignupRequestSchema),
    defaultValues: { email: "", password: "", name: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setGeneralError(null);
    try {
      await signup(values);
      setDone(true);
    } catch (e) {
      const mapped = mapSignupError(e);
      if (mapped.general) setGeneralError(mapped.general);
      if (mapped.fields) {
        for (const [k, v] of Object.entries(mapped.fields)) {
          if (!v) continue;
          setError(k as keyof SignupRequest, { type: "server", message: v });
        }
      }
    }
  });

  if (done) {
    return (
      <div className="notice notice-success space-y-4" role="status">
        <p className="text-sm">
          회원가입이 완료되었습니다. 이제 로그인할 수 있습니다.
        </p>
        <Link href={loginHref} className="action w-full">
          로그인으로 이동
        </Link>
      </div>
    );
  }

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
      <Field
        label="비밀번호"
        error={errors.password?.message}
        hint="8자 이상, 영문/숫자/특수문자를 각 1개 이상 포함해야 합니다."
      >
        <Input
          id="password"
          type="password"
          autoComplete="new-password"
          {...register("password")}
        />
      </Field>
      <Field label="이름" error={errors.name?.message}>
        <Input
          id="name"
          type="text"
          autoComplete="name"
          {...register("name")}
        />
      </Field>
      {generalError ? (
        <p
          role="alert"
          className="text-sm text-[color:var(--color-destructive)]"
        >
          {generalError}
        </p>
      ) : null}
      <Button
        type="submit"
        loading={isSubmitting}
        loadingLabel="가입 중…"
        className="w-full"
      >
        회원가입
      </Button>
      <p className="text-sm muted">
        이미 계정이 있나요?{" "}
        <Link className="underline" href={loginHref}>
          로그인
        </Link>
      </p>
    </form>
  );
}
