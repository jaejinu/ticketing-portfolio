import { test, expect, type Page } from "@playwright/test";

/**
 * 주최자·운영자 콘솔 — API 는 모두 흉내 낸다. 실제 백엔드 흐름은 2026-10-14 수동 브라우저 점검으로 확인했다.
 * 여기서는 화면 규칙(날짜 검사, 공개 조건, 되돌릴 수 없는 동작의 확인 단계, 403 시 폴링 중단)을 고정한다.
 */

async function signedIn(page: Page, roles: string[]) {
  await page
    .context()
    .addCookies([{ name: "tk_rf", value: "browser-fixture", url: "http://localhost:3012" }]);
  await page.route("**/api/auth/**", (route) =>
    route.fulfill({
      json: {
        user: { userId: "user-1", email: "ops@example.com", name: "운영", roles },
        accessToken: "browser-fixture",
        expiresIn: 3600,
      },
    }),
  );
}

test("organizer creates a show and is taken to its management page", async ({ page }) => {
  await signedIn(page, ["USER", "ORGANIZER"]);
  const created: unknown[] = [];
  const newShow = { id: "show-new", title: "새 콘서트", venue: "작은 홀", status: "DRAFT", schedules: [] };
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    if (path === "/organizer/shows" && req.method() === "POST") {
      created.push(req.postDataJSON());
      return route.fulfill({ json: newShow });
    }
    if (path === "/organizer/shows")
      return route.fulfill({ json: { shows: created.length ? [newShow] : [] } });
    return route.fulfill({ json: {} });
  });

  await page.goto("/organizer");
  await expect(page.getByText("아직 등록한 공연이 없어요.")).toBeVisible();
  await page.getByLabel("공연 이름").fill("새 콘서트");
  await page.getByLabel("장소").fill("작은 홀");
  await page.getByRole("button", { name: "공연 만들기" }).click();

  await expect(page).toHaveURL(/\/organizer\/shows\/show-new$/);
  expect(created).toEqual([{ title: "새 콘서트", venue: "작은 홀", description: null }]);
  await expect(page.getByText("준비 중", { exact: true })).toBeVisible();
  // 좌석이 없으면 공개할 수 없다.
  await expect(page.getByRole("button", { name: "공연 공개" })).toBeDisabled();

  // 판매 시작이 공연 시작보다 늦으면 서버에 보내기 전에 막는다.
  await page.getByLabel("공연 시작").fill("2026-12-01T19:00");
  await page.getByLabel("판매 시작").fill("2026-12-02T10:00");
  await expect(page.getByText("판매 시작은 공연 시작보다 늦을 수 없어요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "회차 추가" })).toBeDisabled();
});

test("admin refund asks for confirmation before calling the API", async ({ page }) => {
  await signedIn(page, ["USER", "ADMIN"]);
  const refunds: string[] = [];
  let paymentStatus = "APPROVED";
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path === "/admin/queue/traffic")
      return json({ ip: { allowed: 1, blocked: 0 }, user: { allowed: 1, blocked: 0 }, macroDetected: 0, macroBlockedRequests: 0 });
    if (path === "/admin/queue/queues") return json({ queues: [] });
    if (path === "/admin/queue/blocks") return json({ blocks: [] });
    if (path === "/admin/schedules")
      return json({
        schedules: [
          { scheduleId: "sch-1", showId: "show-1", showTitle: "재즈의 밤", showStatus: "PUBLISHED",
            startsAt: "2026-12-19T10:00:00Z", status: "ON_SALE", seatTotal: 10 },
        ],
      });
    if (path === "/admin/payments")
      return json({
        payments: [{ paymentId: "pay-1", holdId: "hold-1", holderId: "buyer-1", amount: 90000,
          status: paymentStatus, createdAt: "2026-10-14T01:00:00Z" }],
      });
    if (path === "/admin/payments/pay-1/refund") {
      refunds.push(path);
      paymentStatus = "REFUNDED";
      return json({ paymentId: "pay-1", holdId: "hold-1", holderId: "buyer-1", amount: 90000, status: "REFUNDED" });
    }
    return json({});
  });

  await page.goto("/admin");
  await page.getByRole("tab", { name: "결제·환불" }).click();
  await page.getByLabel("회차").selectOption("sch-1");
  await expect(page.getByText("결제 완료 1건")).toBeVisible();
  // 판매 중인 회차는 일괄 환불 버튼이 없다.
  await expect(page.getByText("전체 환불은 판매가 종료된 회차에서만 할 수 있어요.")).toBeVisible();

  await page.getByRole("button", { name: "환불", exact: true }).click();
  expect(refunds).toHaveLength(0);
  await page.getByRole("button", { name: "취소" }).click();
  expect(refunds).toHaveLength(0);

  await page.getByRole("button", { name: "환불", exact: true }).click();
  await page.getByRole("button", { name: "확인" }).click();
  await expect(page.getByText("환불됨")).toBeVisible();
  expect(refunds).toHaveLength(1);
});

test("admin requeues failed outbox events after confirming", async ({ page }) => {
  await signedIn(page, ["USER", "ADMIN"]);
  let failed = 2;
  const requeues: unknown[] = [];
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path === "/admin/queue/traffic")
      return json({ ip: { allowed: 1, blocked: 0 }, user: { allowed: 1, blocked: 0 }, macroDetected: 0, macroBlockedRequests: 0 });
    if (path === "/admin/queue/queues") return json({ queues: [] });
    if (path === "/admin/queue/blocks") return json({ blocks: [] });
    if (path === "/admin/outbox")
      return json({
        pending: 0,
        failed,
        oldestPendingAt: null,
        recentFailed: Array.from({ length: failed }, (_, i) => ({
          id: `evt-${i}`, eventType: "SEAT_SOLD", topic: "seat.sold.v1", retryCount: 10,
          lastError: "TimeoutException: Kafka down", createdAt: "2026-10-14T01:00:00Z",
        })),
      });
    if (path === "/admin/outbox/failed/requeue") {
      requeues.push(req.postDataJSON());
      const n = failed;
      failed = 0;
      return json({ requeued: n });
    }
    return json({});
  });

  await page.goto("/admin");
  await expect(page.getByText("TimeoutException: Kafka down").first()).toBeVisible();
  await page.getByRole("button", { name: "실패한 이벤트 다시 보내기" }).click();
  expect(requeues).toHaveLength(0);
  await page.getByRole("button", { name: "확인" }).click();
  await expect(page.getByText("2건을 발행 대기로 돌렸어요.")).toBeVisible();
  expect(requeues).toEqual([{ ids: [] }]);
});

test("non-admin sees one notice and the console stops polling", async ({ page }) => {
  await signedIn(page, ["USER"]);
  let adminCalls = 0;
  await page.route("**/api/v1/admin/**", (route) => {
    adminCalls++;
    return route.fulfill({ status: 403, json: { code: "FORBIDDEN" } });
  });
  await page.goto("/admin");
  await expect(page.getByText("관리자 권한이 필요한 페이지예요.")).toBeVisible();
  await page.waitForTimeout(6_000); // 폴링 주기(5초)보다 길게
  expect(adminCalls).toBe(1);
});

test("admin refund failure shows pending and recovers without a duplicate request", async ({ page }) => {
  await signedIn(page, ["USER", "ADMIN"]);
  const refunds: string[] = [];
  const paymentStatus = "APPROVED";
  let refundPending = false;
  let recovered = false;
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path === "/admin/queue/traffic")
      return json({ ip: { allowed: 1, blocked: 0 }, user: { allowed: 1, blocked: 0 }, macroDetected: 0, macroBlockedRequests: 0 });
    if (path === "/admin/queue/queues") return json({ queues: [] });
    if (path === "/admin/queue/blocks") return json({ blocks: [] });
    if (path === "/admin/schedules")
      return json({
        schedules: [
          { scheduleId: "sch-1", showId: "show-1", showTitle: "재즈의 밤", showStatus: "PUBLISHED",
            startsAt: "2026-12-19T10:00:00Z", status: "CLOSED", seatTotal: 10 },
        ],
      });
    if (path === "/admin/payments")
      return json({
        payments: [{ paymentId: "pay-1", holdId: "hold-1", holderId: "buyer-1", amount: 90000,
          status: recovered ? "REFUNDED" : paymentStatus, refundPending: refundPending && !recovered, createdAt: "2026-10-14T01:00:00Z" }],
      });
    if (path === "/admin/payments/pay-1/refund") {
      refunds.push(path);
      refundPending = true;
      return route.fulfill({ status: 502, json: { code: "PAYMENT_PG_FAILURE", message: "환불 요청을 저장했습니다. 결제사 응답을 자동으로 다시 확인합니다." } });
    }
    return json({});
  });

  await page.goto("/admin");
  await page.getByRole("tab", { name: "결제·환불" }).click();
  await page.getByLabel("회차").selectOption("sch-1");
  await expect(page.getByText("결제 완료 1건")).toBeVisible();
  await expect(page.getByRole("button", { name: "전체 환불", exact: true })).toBeEnabled();

  await page.getByRole("button", { name: "환불", exact: true }).click();
  expect(refunds).toHaveLength(0);
  await page.getByRole("button", { name: "취소" }).click();
  expect(refunds).toHaveLength(0);

  await page.getByRole("button", { name: "환불", exact: true }).click();
  await page.getByRole("button", { name: "확인" }).click();
  await expect(page.getByText("환불 처리 중", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "환불", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "전체 환불", exact: true })).toBeDisabled();
  recovered = true;
  await expect(page.getByText("환불됨", { exact: true })).toBeVisible({ timeout: 10000 });
  await expect(page.getByText("환불 처리 중", { exact: true })).toHaveCount(0);
  await expect(page.getByText("환불 요청 결과를 확인해 주세요.")).toHaveCount(0);
  expect(refunds).toHaveLength(1);
});

test("batch refund progress updates and confirmation is scoped to the selected schedule", async ({ page }) => {
  await signedIn(page, ["USER", "ADMIN"]);
  let requested = false, recovered = false;
  const calls: string[] = [];
  await page.route("**/api/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname.replace("/api/v1", "");
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path === "/admin/queue/traffic")
      return json({ ip: { allowed: 0, blocked: 0 }, user: { allowed: 0, blocked: 0 }, macroDetected: 0, macroBlockedRequests: 0 });
    if (path === "/admin/queue/queues") return json({ queues: [] });
    if (path === "/admin/queue/blocks") return json({ blocks: [] });
    if (path === "/admin/schedules") return json({ schedules: ["sch-1", "sch-2"].map(scheduleId => ({
      scheduleId, showId: "show-1", showTitle: "재즈", showStatus: "PUBLISHED",
      startsAt: "2026-12-19T10:00:00Z", status: "CLOSED", seatTotal: 10,
    })) });
    if (path === "/admin/payments") return json({ payments: [{
      paymentId: "pay-1", holdId: "hold-1", holderId: "user-1", amount: 90000,
      status: recovered ? "REFUNDED" : "APPROVED", refundPending: requested && !recovered,
    }] });
    if (path.endsWith("/refunds")) {
      calls.push(path); requested = true;
      return json({ refunded: [], failed: ["pay-1"] });
    }
    return json({});
  });
  await page.goto("/admin");
  await page.getByRole("tab", { name: "결제·환불" }).click();
  await page.getByLabel("회차").selectOption("sch-1");
  await page.getByRole("button", { name: "전체 환불", exact: true }).click();
  await expect(page.getByRole("button", { name: "확인", exact: true })).toBeVisible();
  await page.getByLabel("회차").selectOption("sch-2");
  await expect(page.getByRole("button", { name: "확인", exact: true })).toHaveCount(0);
  expect(calls).toEqual([]);
  await page.getByLabel("회차").selectOption("sch-1");
  await page.getByRole("button", { name: "전체 환불", exact: true }).click();
  await page.getByRole("button", { name: "확인", exact: true }).click();
  await expect(page.getByText("환불 0건 완료", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "전체 환불", exact: true })).toBeDisabled();
  recovered = true;
  await expect(page.getByText("환불 1건 완료", { exact: true })).toBeVisible({ timeout: 10000 });
  await expect(page.getByText(/1건은 아직 완료되지/)).toHaveCount(0);
  expect(calls).toEqual(["/admin/schedules/sch-1/refunds"]);
  await page.getByLabel("회차").selectOption("sch-2");
  await expect(page.getByText("환불 1건 완료", { exact: true })).toHaveCount(0);
});

test("payment refresh failure blocks refund until the latest state is available", async ({ page }) => {
  await signedIn(page, ["USER", "ADMIN"]);
  let unavailable = false;
  await page.route("**/api/v1/**", (route) => {
    const path = new URL(route.request().url()).pathname.replace("/api/v1", "");
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path === "/admin/queue/traffic")
      return json({ ip: { allowed: 0, blocked: 0 }, user: { allowed: 0, blocked: 0 }, macroDetected: 0, macroBlockedRequests: 0 });
    if (path === "/admin/queue/queues") return json({ queues: [] });
    if (path === "/admin/queue/blocks") return json({ blocks: [] });
    if (path === "/admin/schedules") return json({ schedules: [{
      scheduleId: "sch-1", showId: "show-1", showTitle: "재즈", showStatus: "PUBLISHED",
      startsAt: "2026-12-19T10:00:00Z", status: "CLOSED", seatTotal: 10,
    }] });
    if (path === "/admin/payments") return unavailable
      ? route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } })
      : json({ payments: [{ paymentId: "pay-1", holdId: "hold-1", holderId: "user-1", amount: 90000, status: "APPROVED" }] });
    return json({});
  });
  await page.goto("/admin");
  await page.getByRole("tab", { name: "결제·환불" }).click();
  await page.getByLabel("회차").selectOption("sch-1");
  await page.getByRole("button", { name: "환불", exact: true }).click();
  unavailable = true;
  await expect(page.getByText("최신 결제 상태를 확인하지 못했어요.")).toBeVisible({ timeout: 20000 });
  await expect(page.getByRole("button", { name: "확인", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "전체 환불", exact: true })).toBeDisabled();
  unavailable = false;
  await page.getByRole("button", { name: "결제 다시 조회" }).click();
  await expect(page.getByRole("button", { name: "확인", exact: true })).toBeEnabled();
  await expect(page.getByText("최신 결제 상태를 확인하지 못했어요.")).toHaveCount(0);
});

test('organizer explicitly configures a discount floor before opening', async ({ page }) => {
  await signedIn(page, ['USER', 'ORGANIZER']);
  const calls: unknown[] = [];
  let percent = 0;
  const schedule = () => ({ id: 'schedule-new', showId: 'show-new', startsAt: '2028-12-01T10:00:00Z', salesStartAt: '2028-11-01T10:00:00Z', status: 'SCHEDULED', seatTotal: 2, discountPercent: percent });
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/discount')) { const body = route.request().postDataJSON(); calls.push(body); percent = body.percent; return route.fulfill({ json: schedule() }); }
    if (path.endsWith('/organizer/shows')) return route.fulfill({ json: { shows: [{ id: 'show-new', title: '할인 공연', venue: '홀', status: 'DRAFT', schedules: [schedule()] }] } });
    if (path.endsWith('/sections')) return route.fulfill({ json: { sections: [] } });
    return route.fulfill({ json: {} });
  });
  await page.goto('/organizer/shows/show-new');
  await expect(page.getByLabel('최대 할인율(%)')).toHaveValue('0');
  await page.getByLabel('최대 할인율(%)').fill('20');
  await expect(page.getByText(/설정 하한: 기준가의 80%/)).toBeVisible();
  await expect(page.getByRole('button', { name: '할인 설정 저장' })).toBeDisabled();
  await page.getByLabel('할인 설정 사유').fill('사전 할인');
  await page.getByRole('button', { name: '할인 설정 저장' }).click();
  await expect.poll(() => calls.length).toBe(1);
  expect(calls[0]).toEqual({ percent: 20, reason: '사전 할인' });
});

test('sales report keeps whole-result totals while moving pages', async ({ page }) => {
  await signedIn(page, ['USER', 'ADMIN']);
  await page.route('**/api/v1/**', route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/payment-report')) return route.fulfill({ json: { payments: [{ paymentId: `payment-${url.searchParams.get('page')}`, holdId: 'hold', holderId: 'buyer', amount: 10000, status: 'APPROVED' }], totalElements: 30, totalPages: 2, page: Number(url.searchParams.get('page')), totals: [{ status: 'APPROVED', refundPending: false, count: 30, amount: 300000 }] } });
    if (url.pathname.endsWith('/schedules')) return route.fulfill({ json: { schedules: [] } });
    return route.fulfill({ json: {} });
  });
  await page.goto('/admin');
  await page.getByRole('tab', { name: '판매 리포트' }).click();
  await expect(page.getByText('검색 결과 30건')).toBeVisible();
  await expect(page.getByText(/30건 · 300,000/)).toBeVisible();
  await page.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page.getByText(/결제 payment-1/)).toBeVisible();
  await expect(page.getByText(/30건 · 300,000/)).toBeVisible();
});

test('manual macro rule requires a reason and confirmation', async ({ page }) => {
  await signedIn(page, ['USER', 'ADMIN']);
  const rules: unknown[] = [];
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/rules')) { rules.push(route.request().postDataJSON()); return route.fulfill({ status: 204 }); }
    if (path.endsWith('/allows') || path.endsWith('/blocks')) return route.fulfill({ json: { blocks: [] } });
    return route.fulfill({ json: {} });
  });
  await page.goto('/admin');
  await page.getByLabel('차단·허용 대상').fill('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
  await expect(page.getByRole('button', { name: '규칙 적용' })).toBeDisabled();
  await page.getByLabel('규칙 적용 사유').fill('테스트 차단');
  await page.getByRole('button', { name: '규칙 적용' }).click();
  expect(rules).toHaveLength(0);
  await page.getByRole('button', { name: '확인', exact: true }).click();
  await expect.poll(() => rules.length).toBe(1);
  expect(rules[0]).toEqual({ scope: 'user', key: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', allow: false, seconds: 600, reason: '테스트 차단' });
});
