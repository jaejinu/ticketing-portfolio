"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listShows, getShow, listSections } from "@/lib/api/shows";
import { createAlert, disableAlert, listMyAlerts } from "@/lib/api/alerts";
import type { Alert, AlertChannel } from "@/lib/api/schemas";
import { subscribe } from "@/lib/stomp/client";
import { ApiRequestError } from "@/lib/api/client";
import { Field } from "@/components/ui/Field";
import { Select } from "@/components/ui/Select";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Button } from "@/components/ui/Button";
import { Input } from "@/components/ui/Input";
import { Notice } from "@/components/ui/Notice";
import { dateTime, won } from "@/lib/format/display";

const channels: Record<AlertChannel, string> = {
  FCM: "앱 알림",
  SMTP: "이메일",
  WEBHOOK: "웹훅",
};
export default function MyAlertsPage() {
  return (
    <div className="hybrid-container page-space space-y-8">
      <header className="space-y-3">
        <p className="eyebrow">PRICE ALERTS</p>
        <h1 className="page-title">원하는 가격에 더 가까이.</h1>
        <p className="muted">
          관심 있는 공연과 구역을 고르고, 알림을 받을 가격을 정하세요.
        </p>
      </header>
      <div className="booking-layout">
        <AlertList />
        <CreateAlertForm />
      </div>
    </div>
  );
}
function CreateAlertForm() {
  const client = useQueryClient();
  const [showId, setShowId] = useState("");
  const [scheduleId, setScheduleId] = useState("");
  const [sectionId, setSectionId] = useState("");
  const [threshold, setThreshold] = useState("");
  const [channel, setChannel] = useState<AlertChannel>("FCM");
  const shows = useQuery({
    queryKey: ["shows"],
    queryFn: listShows,
    staleTime: 60000,
  });
  const show = useQuery({
    queryKey: ["show", showId],
    queryFn: () => getShow(showId),
    enabled: !!showId,
  });
  const sections = useQuery({
    queryKey: ["sections", showId, scheduleId],
    queryFn: () => listSections(showId, scheduleId),
    enabled: !!showId && !!scheduleId,
  });
  const schedules =
    show.data?.schedules?.filter(
      (s) => s.status === "ON_SALE" || s.status === "SCHEDULED",
    ) ?? [];
  const mutation = useMutation({
    mutationFn: () =>
      createAlert({
        scheduleId,
        sectionId,
        type: "PRICE_DROP_BELOW",
        thresholdPrice: Number(threshold),
        channel,
      }),
    onSuccess: () => {
      setThreshold("");
      void client.invalidateQueries({ queryKey: ["my-alerts"] });
    },
  });
  const valid =
    !!sectionId &&
    sections.data?.some((s) => s.id === sectionId) &&
    schedules.some((s) => s.id === scheduleId) &&
    Number.isSafeInteger(Number(threshold)) &&
    Number(threshold) > 0;
  const error = shows.isError || show.isError || sections.isError;
  return (
    <aside className="panel booking-sidebar space-y-6">
      <h2 className="text-xl font-semibold">새 가격 알림</h2>
      <form
        className="space-y-5"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid && !mutation.isPending) mutation.mutate();
        }}
      >
        <Field label="공연">
          <Select
            value={showId}
            disabled={shows.isLoading || mutation.isPending}
            onChange={(v) => {
              setShowId(v);
              setScheduleId("");
              setSectionId("");
              mutation.reset();
            }}
            options={
              shows.data?.map((s) => ({ value: s.id, label: s.title })) ?? []
            }
            placeholder="공연 선택"
          />
        </Field>
        <Field label="관람 회차">
          <Select
            value={scheduleId}
            disabled={!showId || show.isFetching || mutation.isPending}
            onChange={(v) => {
              setScheduleId(v);
              setSectionId("");
            }}
            options={schedules.map((s) => ({
              value: s.id,
              label: dateTime(s.startsAt),
            }))}
            placeholder={
              !showId
                ? "공연을 먼저 선택하세요"
                : show.isFetching
                  ? "회차 확인 중…"
                  : schedules.length
                    ? "회차 선택"
                    : "알림 가능한 회차가 없어요"
            }
          />
        </Field>
        <Field label="좌석 구역">
          <Select
            value={sectionId}
            disabled={!scheduleId || sections.isFetching || mutation.isPending}
            onChange={setSectionId}
            options={
              sections.data?.map((s) => ({
                value: s.id,
                label: `${s.name} · 기준 ${won(s.basePrice)}`,
              })) ?? []
            }
            placeholder={!scheduleId ? "회차를 먼저 선택하세요" : "구역 선택"}
          />
        </Field>
        <Field label="알림 받을 가격 (원)">
          <Input
            type="number"
            inputMode="numeric"
            min={1}
            step={1}
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            placeholder="예: 80000"
            disabled={mutation.isPending}
            aria-describedby="price-help"
          />
        </Field>
        <p id="price-help" className="text-xs muted">
          선택한 구역의 가격이 입력 금액 이하가 되면 알립니다.
        </p>
        <Field label="받는 방법">
          <Select
            value={channel}
            disabled={mutation.isPending}
            onChange={(v) => setChannel(v as AlertChannel)}
            options={Object.entries(channels).map(([value, label]) => ({
              value,
              label,
            }))}
          />
        </Field>
        {error && (
          <Notice
            tone="error"
            title="공연 정보를 확인하지 못했어요."
            action={
              <Button
                type="button"
                variant="outline"
                onClick={() => {
                  void shows.refetch();
                  if (showId) void show.refetch();
                  if (scheduleId) void sections.refetch();
                }}
              >
                다시 확인
              </Button>
            }
          />
        )}
        {mutation.isError && (
          <Notice tone="error" title="알림을 등록하지 못했어요.">
            {mutation.error instanceof ApiRequestError
              ? mutation.error.payload?.message
              : "연결을 확인하고 다시 시도해 주세요."}
          </Notice>
        )}
        {mutation.isSuccess && (
          <div role="status">
            <Notice tone="success" title="가격 알림을 등록했어요." />
          </div>
        )}
        <Button
          type="submit"
          className="w-full"
          disabled={!valid || error || mutation.isPending}
        >
          {mutation.isPending ? "등록 중…" : "가격 알림 등록"}
        </Button>
        <p className="text-xs muted">
          현재 체험 환경에서는 알림 발송도 테스트 서비스로 처리됩니다. 알림은
          좌석을 확보하지 않습니다.
        </p>
      </form>
    </aside>
  );
}
function AlertList() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["my-alerts"],
    queryFn: listMyAlerts,
    refetchInterval: 15000,
  });
  const disable = useMutation({
    mutationFn: disableAlert,
    onSuccess: () => void client.invalidateQueries({ queryKey: ["my-alerts"] }),
  });
  useEffect(() => {
    const sub = subscribe(
      "/user/queue/alerts",
      () => void client.invalidateQueries({ queryKey: ["my-alerts"] }),
    );
    return () => sub?.unsubscribe();
  }, [client]);
  return (
    <section className="space-y-5">
      <h2 className="text-xl font-semibold">내 가격 알림</h2>
      {query.isLoading && <Notice title="알림을 불러오는 중…" />}
      {query.isError && (
        <Notice
          tone="error"
          title="알림을 불러오지 못했어요."
          action={
            <Button variant="outline" onClick={() => void query.refetch()}>
              다시 확인
            </Button>
          }
        />
      )}
      {query.isSuccess && !query.data.length && (
        <Notice title="아직 등록한 알림이 없어요.">
          공연과 목표 가격을 정해 첫 알림을 등록해 보세요.
        </Notice>
      )}
      {disable.isError && (
        <Notice tone="error" title="알림을 해제하지 못했어요.">
          잠시 후 다시 시도해 주세요.
        </Notice>
      )}
      <ul className="space-y-4">
        {query.data?.map((alert) => (
          <li key={alert.alertId}>
            <AlertCard
              alert={alert}
              onDisable={() => disable.mutate(alert.alertId)}
              busy={disable.isPending}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}
function AlertCard({
  alert,
  onDisable,
  busy,
}: {
  alert: Alert;
  onDisable: () => void;
  busy: boolean;
}) {
  const shows = useQuery({
    queryKey: ["shows"],
    queryFn: listShows,
    staleTime: 60000,
  });
  const show = shows.data?.find((s) =>
    s.schedules?.some((sc) => sc.id === alert.scheduleId),
  );
  const schedule = show?.schedules?.find((sc) => sc.id === alert.scheduleId);
  const sections = useQuery({
    queryKey: ["sections", show?.id, alert.scheduleId],
    queryFn: () => listSections(show!.id, alert.scheduleId),
    enabled: !!show,
  });
  const section = sections.data?.find((s) => s.id === alert.sectionId);
  const labels = {
    ACTIVE: "가격 확인 중",
    TRIGGERED: "알림 발송 완료",
    DISABLED: "알림 해제됨",
  };
  return (
    <article className="surface-card space-y-5">
      <div className="data-row text-sm">
        <StatusBadge
          tone={alert.status === "TRIGGERED" ? "success" : "neutral"}
        >
          {labels[alert.status]}
        </StatusBadge>
        <span className="muted">{channels[alert.channel]}</span>
      </div>
      <div className="space-y-2">
        <h3 className="text-xl font-semibold">
          {show?.title ?? "공연 정보 확인 중"}
        </h3>
        <p className="text-sm muted">
          {schedule ? dateTime(schedule.startsAt) : "일정 확인 불가"} ·{" "}
          {section?.name ?? "구역 확인 중"}
        </p>
      </div>
      <div className="data-row">
        <span className="muted text-sm">목표 가격</span>
        <strong className="text-2xl">{won(alert.thresholdPrice)} 이하</strong>
      </div>
      {alert.triggeredPrice != null && (
        <p className="text-sm text-[color:var(--color-success)]">
          알림 당시 {won(alert.triggeredPrice)}
          {alert.triggeredAt && ` · ${dateTime(alert.triggeredAt)}`}
        </p>
      )}
      <div className="flex flex-wrap justify-between gap-3 border-t border-[color:var(--color-border)] pt-4">
        {show && (
          <Link
            className="inline-flex items-center min-h-11 text-sm"
            href={`/shows/${show.id}`}
          >
            공연 확인 ↗
          </Link>
        )}
        {alert.status === "ACTIVE" && (
          <Button
            size="sm"
            variant="outline"
            onClick={onDisable}
            disabled={busy}
          >
            {busy ? "처리 중…" : "알림 해제"}
          </Button>
        )}
      </div>
    </article>
  );
}
