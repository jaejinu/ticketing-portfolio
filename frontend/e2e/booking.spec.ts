import { test, expect, type Page } from "@playwright/test";

const schedule = {
  id: "schedule-1",
  showId: "show-1",
  startsAt: "2026-12-19T10:00:00Z",
  status: "ON_SALE",
};
const show = {
  id: "show-1",
  title: "NOCTURNE — 재즈의 밤",
  venue: "서울 재즈홀",
  description:
    "겨울밤을 채우는 깊은 울림. 함께 만드는 라이브의 순간을 만나세요.",
  posterUrl: "/posters/jazz.svg",
  schedules: [schedule],
};
const section = { id: "section-1", name: "R석", grade: "R", basePrice: 80000 };
const breakdown = [
  {
    sectionId: section.id,
    sectionName: section.name,
    grade: "R",
    count: 2,
    unitPrice: 90000,
  },
];
const hold = {
  holdId: "hold-1",
  holderId: "user-1",
  showId: show.id,
  scheduleId: schedule.id,
  seatIds: ["seat-1", "seat-2"],
  sectionBreakdown: breakdown,
  totalAmount: 180000,
  status: "ACTIVE",
  createdAt: new Date().toISOString(),
};
const payment = {
  paymentId: "payment-1",
  holdId: hold.holdId,
  holderId: hold.holderId,
  amount: hold.totalAmount,
  status: "APPROVED",
  createdAt: hold.createdAt,
};

async function mockApi(
  page: Page,
  options: { expired?: boolean; retryPayment?: boolean } = {},
) {
  const requests: { key: string | undefined; body: unknown }[] = [];
  await page
    .context()
    .addCookies([
      { name: "tk_rf", value: "browser-fixture", url: "http://localhost:3012" },
    ]);
  await page.route("**/api/auth/**", (route) =>
    route.fulfill({
      json: {
        user: {
          userId: "user-1",
          email: "test@example.com",
          name: "테스트",
          roles: ["USER"],
        },
        accessToken: "browser-fixture",
        expiresIn: 3600,
      },
    }),
  );
  await page.route("**/api/v1/**", async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace("/api/v1", "");
    const json = (value: unknown) => route.fulfill({ json: value });
    if (path === "/shows")
      return json({
        shows: [
          show,
          {
            ...show,
            id: "show-2",
            title: "다가오는 클래식",
            posterUrl: "/posters/classic.svg",
            schedules: [{ ...schedule, id: "schedule-2", status: "SCHEDULED" }],
          },
        ],
      });
    if (path === "/shows/show-1") return json(show);
    if (path.endsWith("/sections")) return json({ sections: [section] });
    if (path.endsWith("/seats"))
      return json({
        seats: Array.from({ length: 24 }, (_, i) => ({
          id: `seat-${i + 1}`,
          sectionId: section.id,
          rowLabel: i < 12 ? "A" : "B",
          colNo: (i % 12) + 1,
          status: i === 9 ? "HELD" : i === 10 ? "SOLD" : "AVAILABLE",
        })),
      });
    if (path.endsWith("/pricing") || path.endsWith("/history"))
      return json({
        ticks: [
          {
            tickId: "tick-1",
            scheduleId: schedule.id,
            sectionId: section.id,
            basePrice: 80000,
            currentPrice: 90000,
            occurredAt: new Date().toISOString(),
          },
        ],
      });
    if (path.endsWith("/candles"))
      return json({ sectionId: section.id, interval: "1m", candles: [] });
    if (path.startsWith("/queue/"))
      return json({
        state: "ADMITTED",
        ticket: "ticket-1",
        scheduleId: schedule.id,
        position: 0,
        estimatedWaitSeconds: 0,
      });
    if (path.startsWith("/seat-holds"))
      return json({
        ...hold,
        status: options.expired ? "EXPIRED" : "ACTIVE",
        expiresAt: new Date(
          Date.now() + (options.expired ? -1000 : 300000),
        ).toISOString(),
      });
    if (path === "/payments" && req.method() === "POST") {
      requests.push({
        key: req.headers()["idempotency-key"],
        body: req.postDataJSON(),
      });
      if (options.retryPayment && requests.length === 1)
        return route.abort("failed");
      return json({ ...payment, status: "PENDING" });
    }
    if (path === "/payments/payment-1") return json(payment);
    if (path === "/payments")
      return json({
        payments: requests.length
          ? [{ ...payment, ...hold, status: "APPROVED" }]
          : [],
      });
    return route.fulfill({
      status: 404,
      json: { code: "UNEXPECTED_FIXTURE_ROUTE", message: path },
    });
  });
  return requests;
}

async function noPageOverflow(page: Page) {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
}

test("anonymous booking entry requires login and preserves the schedule", async ({ page }) => {
  await mockApi(page);
  await page.context().clearCookies();
  await page.route("**/api/auth/me", (route) =>
    route.fulfill({ status: 401, json: { code: "UNAUTHORIZED" } }),
  );
  const queueRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/queue/")) queueRequests.push(request.url());
  });
  await page.goto("/shows/show-1");
  await expect(page.getByRole("heading", { name: show.title })).toBeVisible();
  await page.getByRole("link", { name: /좌석 선택하기/ }).click();
  await expect(page).toHaveURL(/\/login\?/);
  expect(new URL(page.url()).searchParams.get("next")).toBe("/shows/show-1/schedule-1/queue");
  await page.goto("/shows/show-1/schedule-1/seats?section=R");
  await expect(page).toHaveURL(/\/login\?/);
  expect(new URL(page.url()).searchParams.get("next")).toBe("/shows/show-1/schedule-1/seats?section=R");
  expect(queueRequests).toHaveLength(0);
  await page.route("**/api/auth/login", async (route) => {
    await page.context().addCookies([{ name: "tk_rf", value: "renewed-fixture", url: "http://localhost:3012" }]);
    await route.fulfill({ json: { user: { userId: "user-1", email: "test@example.com", name: "테스트", roles: ["USER"] }, accessToken: "fixture", expiresIn: 3600 } });
  });
  await page.getByLabel("이메일", { exact: true }).fill("test@example.com");
  await page.getByLabel("비밀번호", { exact: true }).fill("Test1234!");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL("/shows/show-1/schedule-1/seats?section=R");
  await expect(page.getByRole("button", { name: "R석 A열 1번, 선택 가능" })).toBeVisible();
});

test("expired authentication during queue entry returns to login", async ({ page }) => {
  await mockApi(page);
  await page.route("**/api/v1/queue/**", (route) =>
    route.fulfill({ status: 401, json: { code: "AUTHENTICATION_REQUIRED" } }),
  );
  await page.route("**/api/auth/refresh", (route) =>
    route.fulfill({ status: 401, json: { code: "UNAUTHORIZED" } }),
  );
  await page.goto("/shows/show-1/schedule-1/queue");
  await expect(page).toHaveURL(/\/login\?/);
  expect(new URL(page.url()).searchParams.get("next")).toBe("/shows/show-1/schedule-1/queue");
  await expect(page.getByText("AUTHENTICATION_REQUIRED", { exact: false })).toHaveCount(0);
});

test("queue rejection replaces the primary action with re-entry", async ({ page }, info) => {
  await mockApi(page);
  await page.route("**/api/v1/seat-holds", (route) =>
    route.fulfill({ status: 403, json: { code: "QUEUE_REJECTED", message: "expired" } }),
  );
  await page.goto("/shows/show-1/schedule-1/seats");
  await page.getByRole("button", { name: "R석 A열 1번, 선택 가능" }).click();
  const action = page.getByRole("region", { name: "예매 진행", exact: true });
  await action.getByRole("button", { name: /이 가격으로 좌석 확보/ }).click();
  const recovery = action.getByRole("link", { name: /대기열 다시 입장/ });
  await expect(recovery).toBeVisible();
  await expect(action.getByRole("button", { name: /좌석 확보/ })).toHaveCount(0);
  if (info.project.name.startsWith("mobile")) {
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(recovery).toBeInViewport();
  }
  await recovery.click();
  await expect(page).toHaveURL(/\/seats$/);
  await expect(action.getByRole("button", { name: /이 가격으로 좌석 확보/ })).toBeVisible();
});

test("price lookup recovery stays reachable on a narrow mobile screen", async ({ page }, info) => {
  if (info.project.name.startsWith("mobile")) await page.setViewportSize({ width: 320, height: 568 });
  await mockApi(page);
  let recovered = false;
  await page.route("**/api/v1/shows/*/schedules/*/pricing", (route) =>
    recovered
      ? route.fulfill({ json: { ticks: [{ tickId: "tick-1", scheduleId: schedule.id, sectionId: section.id, basePrice: 80000, currentPrice: 90000, occurredAt: new Date().toISOString() }] } })
      : route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } }),
  );
  await page.goto("/shows/show-1/schedule-1/seats");
  await page.getByRole("button", { name: "R석 A열 1번, 선택 가능" }).click();
  const action = page.getByRole("region", { name: "예매 진행", exact: true });
  const retry = action.getByRole("button", { name: "가격 다시 확인" });
  await expect(retry).toBeVisible({ timeout: 15000 });
  if (info.project.name.startsWith("mobile")) {
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(retry).toBeInViewport();
  }
  await noPageOverflow(page);
  recovered = true;
  await retry.click();
  await expect(action.getByRole("button", { name: /이 가격으로 좌석 확보/ })).toBeEnabled();
  if (info.project.name.startsWith("mobile")) {
    await expect(action).toContainText("1석 선택 · 예상 총액 90,000원");
    await page.screenshot({ path: info.outputPath("narrow-seat-recovery.png") });
  }
});

test("seat map stays operable with horizontal scrolling and keyboard input", async ({ page }, info) => {
  await mockApi(page);
  await page.goto("/shows/show-1/schedule-1/seats");
  for (const viewport of info.project.name.startsWith("mobile")
    ? [{ width: 320, height: 568 }, { width: 667, height: 375 }]
    : [{ width: 1440, height: 1000 }]) {
    await page.setViewportSize(viewport);
    const lastSeat = page.getByRole("button", { name: /R석 A열 12번,/ });
    await lastSeat.click();
    const selected = await lastSeat.getAttribute("aria-pressed");
    await lastSeat.focus();
    await page.keyboard.press("Space");
    await expect(lastSeat).toHaveAttribute("aria-pressed", selected === "true" ? "false" : "true");
    await noPageOverflow(page);
    if (info.project.name.startsWith("mobile")) {
      const size = await lastSeat.boundingBox();
      expect(size!.width).toBeGreaterThanOrEqual(44);
      expect(size!.height).toBeGreaterThanOrEqual(44);
      await expect(page.getByRole("region", { name: "예매 진행", exact: true })).toBeInViewport({ ratio: 1 });
      await page.screenshot({ path: info.outputPath(`seat-map-${viewport.width}.png`) });
    }
  }
});

test("catalog search and filters, responsive layout", async ({
  page,
}, info) => {
  await mockApi(page);
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: show.title }).first(),
  ).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({ path: info.outputPath("home.png"), fullPage: true });
  await page.getByRole("searchbox").fill("없는 공연");
  await expect(page.getByText("조건에 맞는 공연이 없어요.")).toBeVisible();
  await page.getByRole("button", { name: "검색·필터 초기화" }).click();
  await page.getByRole("button", { name: "오픈 예정", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("1개의 공연");
});

test("detail → admission → seats → hold → pending payment → confirmation → tickets", async ({
  page,
}, info) => {
  const requests = await mockApi(page);
  await page.goto("/shows/show-1");
  await expect(page.getByRole("heading", { name: show.title })).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({
    path: info.outputPath("detail.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: /좌석 선택하기/ }).click();
  await expect(page).toHaveURL(/\/seats$/);
  const seat = (n: number) =>
    page.getByRole("button", { name: new RegExp(`R석 A열 ${n}번,`) });
  await expect(seat(10)).toBeDisabled();
  await expect(seat(11)).toBeDisabled();
  for (const n of [1, 2, 3, 4, 5]) await seat(n).click();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "한 번에 최대 4석까지 선택할 수 있어요." }),
  ).toBeVisible();
  await expect(page.locator(".seat[aria-pressed=true]")).toHaveCount(4);
  await seat(3).click();
  await seat(4).click();
  await expect(page.getByText("180,000원", { exact: true })).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({ path: info.outputPath("seats.png"), fullPage: true });
  await page.getByRole("button", { name: "이 가격으로 좌석 확보" }).click();
  await expect(page).toHaveURL("/checkout/hold-1");
  await expect(page.getByRole("timer")).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({
    path: info.outputPath("checkout.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "180,000원 결제하기" }).click();
  await expect(
    page.getByRole("heading", { name: "그날의 밤, 당신의 자리가 준비됐어요." }),
  ).toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0]!.body).toEqual({ holdId: "hold-1", amount: 180000 });
  await page.screenshot({
    path: info.outputPath("confirmation.png"),
    fullPage: true,
  });
  await page.getByRole("link", { name: /내 티켓 확인하기/ }).click();
  await expect(page.getByText("예매 확정", { exact: true })).toBeVisible();
});

test("expired hold cannot submit payment", async ({ page }) => {
  const requests = await mockApi(page, { expired: true });
  await page.goto("/checkout/hold-1");
  await expect(page.getByText("좌석 확보 시간이 끝났어요.")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "180,000원 결제하기" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: /좌석 다시 선택하기/ }),
  ).toBeVisible();
  expect(requests).toHaveLength(0);
});

test("unknown payment result retries with original idempotency key", async ({
  page,
}) => {
  const requests = await mockApi(page, { retryPayment: true });
  await page.goto("/checkout/hold-1");
  await page.getByRole("button", { name: "180,000원 결제하기" }).click();
  await page.getByRole("button", { name: "이전 결제 요청 결과 확인" }).click();
  await expect(
    page.getByRole("heading", { name: "그날의 밤, 당신의 자리가 준비됐어요." }),
  ).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[0]!.key).toBeTruthy();
  expect(requests[0]!.key).toBe(requests[1]!.key);
});

test("price lookup failure prevents holding seats at an unknown price", async ({
  page,
}) => {
  await mockApi(page);
  await page.route("**/api/v1/shows/*/schedules/*/pricing", (route) =>
    route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } }),
  );
  await page.goto("/shows/show-1/schedule-1/seats");
  await page.getByRole("button", { name: "R석 A열 1번, 선택 가능" }).click();
  await expect(
    page.getByRole("button", { name: "이 가격으로 좌석 확보" }),
  ).toBeDisabled();
});

test("payment retry preserves its UUID when randomUUID is unavailable on LAN HTTP", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window.crypto, "randomUUID", { value: undefined });
  });
  const requests = await mockApi(page, { retryPayment: true });
  await page.goto("/checkout/hold-1");
  await page.getByRole("button", { name: "180,000원 결제하기" }).click();
  await page.getByRole("button", { name: "이전 결제 요청 결과 확인" }).click();
  await expect(page.getByRole("heading", { name: "그날의 밤, 당신의 자리가 준비됐어요." })).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[0]!.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(requests[1]!.key).toBe(requests[0]!.key);
});

test("waiting queue displays server position and recovers after a connection failure", async ({
  page,
}, info) => {
  await mockApi(page);
  let offline = false;
  await page.route("**/api/v1/queue/**", (route) =>
    offline
      ? route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } })
      : route.fulfill({
          json: {
            state: "WAITING",
            ticket: "ticket-1",
            scheduleId: schedule.id,
            position: 38,
            estimatedWaitSeconds: 120,
          },
        }),
  );
  await page.goto("/shows/show-1/schedule-1/queue");
  await expect(page.getByText("약 2분", { exact: true })).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({ path: info.outputPath("queue.png"), fullPage: true });
  offline = true;
  await expect(
    page.getByText(
      "대기 상태를 확인하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.",
    ),
  ).toBeVisible({ timeout: 15000 });
  offline = false;
  await page.getByRole("button", { name: "다시 시도", exact: true }).click();
  await expect(page.getByText("약 2분", { exact: true })).toBeVisible();
});

test("signup preserves booking destination through login", async ({
  page,
}, info) => {
  await mockApi(page);
  const user = {
    userId: "user-1",
    email: "test@example.com",
    name: "테스트",
    roles: ["USER"],
  };
  await page.route("**/api/auth/signup", (route) =>
    route.fulfill({
      json: { userId: user.userId, email: user.email, name: user.name },
    }),
  );
  await page.route("**/api/auth/login", (route) =>
    route.fulfill({
      json: { user, accessToken: "fixture-token", expiresIn: 3600 },
    }),
  );
  await page.goto("/login?next=%2Fcheckout%2Fhold-1");
  await noPageOverflow(page);
  await page.screenshot({ path: info.outputPath("login.png"), fullPage: true });
  await page.getByRole("link", { name: "회원가입", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "다음 장면을 함께해요." }),
  ).toBeVisible();
  await page.getByLabel("이메일", { exact: true }).fill(user.email);
  await page.getByLabel("비밀번호", { exact: true }).fill("Test1234!");
  await page.getByLabel("이름", { exact: true }).fill(user.name);
  await page.getByRole("button", { name: "회원가입", exact: true }).click();
  await page.getByRole("link", { name: "로그인으로 이동" }).click();
  await page.getByLabel("이메일", { exact: true }).fill(user.email);
  await page.getByLabel("비밀번호", { exact: true }).fill("Test1234!");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL("/checkout/hold-1");
});

test("form errors describe inputs and pending login blocks duplicate submission", async ({
  page,
}) => {
  await mockApi(page);
  await page.goto("/signup");
  await page.getByRole("button", { name: "회원가입", exact: true }).click();
  const password = page.getByLabel("비밀번호", { exact: true });
  await expect(password).toHaveAttribute("aria-invalid", "true");
  await expect(password).toHaveAccessibleDescription(
    /8자 이상.*비밀번호는 8자 이상/,
  );
  await expect(page.getByLabel("이메일", { exact: true })).toBeFocused();
  await noPageOverflow(page);

  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  let requests = 0;
  await page.route("**/api/auth/login", async (route) => {
    requests++;
    await gate;
    await route.fulfill({
      json: {
        user: {
          userId: "user-1",
          email: "test@example.com",
          name: "테스트",
          roles: ["USER"],
        },
        accessToken: "fixture-token",
        expiresIn: 3600,
      },
    });
  });
  await page.goto("/login");
  await page.getByLabel("이메일", { exact: true }).fill("test@example.com");
  await page.getByLabel("비밀번호", { exact: true }).fill("Test1234!");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  const submit = page.getByRole("button", { name: "로그인 중…", exact: true });
  await expect(submit).toBeDisabled();
  await expect(submit).toHaveAttribute("aria-busy", "true");
  await expect.poll(() => requests).toBe(1);
  await page.keyboard.press("Enter");
  release();
  await expect(page).toHaveURL("/");
  expect(requests).toBe(1);
});

test("login rejects external return destinations", async ({ page }) => {
  await mockApi(page);
  await page.goto("/login?next=%2F%2Fexample.com");
  await page.getByLabel("이메일", { exact: true }).fill("test@example.com");
  await page.getByLabel("비밀번호", { exact: true }).fill("Test1234!");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL("/");
});

test("ticket detail resolves seat numbers and handles unknown booking", async ({
  page,
}, info) => {
  await mockApi(page);
  await page.route("**/api/v1/payments", (route) =>
    route.fulfill({
      json: { payments: [{ ...hold, ...payment, status: "APPROVED" }] },
    }),
  );
  await page.goto("/me/tickets/payment-1");
  await expect(page.getByText("R석 · A열 1번", { exact: true })).toBeVisible();
  await expect(page.getByText("R석 · A열 2번", { exact: true })).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({
    path: info.outputPath("ticket-detail.png"),
    fullPage: true,
  });
  await page.goto("/me/tickets/unknown");
  await expect(page.getByText("예매 내역을 찾을 수 없어요.")).toBeVisible();
});

test("price alerts register and disable with performance labels", async ({
  page,
}, info) => {
  await mockApi(page);
  let registered = false,
    disabled = false;
  const alert = {
    alertId: "alert-1",
    userId: "user-1",
    scheduleId: schedule.id,
    sectionId: section.id,
    type: "PRICE_DROP_BELOW",
    thresholdPrice: 80000,
    channel: "FCM",
    status: "ACTIVE",
    createdAt: new Date().toISOString(),
  };
  await page.route("**/api/v1/alerts**", (route) => {
    const method = route.request().method();
    if (method === "POST") {
      registered = true;
      expect(route.request().postDataJSON().thresholdPrice).toBe(80000);
    }
    if (method === "DELETE") disabled = true;
    const current = { ...alert, status: disabled ? "DISABLED" : "ACTIVE" };
    return route.fulfill({
      json:
        method === "GET" ? { alerts: registered ? [current] : [] } : current,
    });
  });
  await page.goto("/me/alerts");
  await expect(page.getByText("아직 등록한 알림이 없어요.")).toBeVisible();
  await page
    .getByRole("combobox", { name: "공연", exact: true })
    .selectOption(show.id);
  await page
    .getByRole("combobox", { name: "관람 회차", exact: true })
    .selectOption(schedule.id);
  await page
    .getByRole("combobox", { name: "좌석 구역", exact: true })
    .selectOption(section.id);
  await page.getByLabel("알림 받을 가격 (원)", { exact: true }).fill("80000");
  await page
    .getByRole("button", { name: "가격 알림 등록", exact: true })
    .click();
  await expect(page.getByText("가격 알림을 등록했어요.")).toBeVisible();
  await expect(page.getByRole("heading", { name: show.title })).toBeVisible();
  await noPageOverflow(page);
  await page.screenshot({
    path: info.outputPath("alerts.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "알림 해제", exact: true }).click();
  await expect(page.getByText("알림 해제됨")).toBeVisible();
});

test("switching accounts clears the previous booking cache", async ({
  page,
}) => {
  await mockApi(page);
  let nextUser = false;
  await page.route("**/api/v1/payments", (route) =>
    route.fulfill({
      json: {
        payments: nextUser ? [] : [{ ...hold, ...payment, status: "APPROVED" }],
      },
    }),
  );
  await page.route("**/api/auth/logout", (route) =>
    route.fulfill({ status: 204 }),
  );
  await page.route("**/api/auth/login", (route) => {
    nextUser = true;
    return route.fulfill({
      json: {
        user: {
          userId: "user-2",
          email: "second@example.com",
          name: "두 번째 계정",
          roles: ["USER"],
        },
        accessToken: "second-fixture",
        expiresIn: 3600,
      },
    });
  });
  await page.goto("/me/tickets");
  await expect(page.getByText("예매 확정", { exact: true })).toBeVisible();
  await page.locator("header a[href='/me']:visible").click();
  await page
    .getByRole("button", { name: "로그아웃", exact: true })
    .last()
    .click();
  await expect(page).toHaveURL("/");
  await page.getByRole("link", { name: "로그인", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "다시 만나 반가워요." }),
  ).toBeVisible();
  await page.getByLabel("이메일", { exact: true }).fill("second@example.com");
  await page.getByLabel("비밀번호", { exact: true }).fill("Test1234!");
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL("/");
  await page
    .getByRole("navigation", { name: "주요 메뉴" })
    .getByRole("link", { name: "내 티켓", exact: true })
    .click();
  await expect(page.getByText("아직 예매한 공연이 없어요.")).toBeVisible();
  await expect(page.getByText("예매 확정", { exact: true })).toHaveCount(0);
});

test("anonymous session does not interrupt public catalog queries", async ({
  page,
}) => {
  await mockApi(page);
  await page.context().clearCookies();
  await page.route("**/api/auth/**", (route) =>
    route.fulfill({ status: 401, json: { code: "UNAUTHORIZED" } }),
  );
  await page.route("**/api/v1/shows", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 400));
    await route.fulfill({ json: { shows: [show] } });
  });
  await page.goto("/shows");
  await expect(page.getByRole("heading", { name: show.title })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("1개의 공연");
});

test("seat conflict preserves available choices and keeps mobile action reachable", async ({
  page,
}, info) => {
  await mockApi(page);
  let conflict = false;
  let posted: string[] = [];
  await page.route("**/api/v1/shows/*/schedules/*/seats", (route) =>
    route.fulfill({
      json: {
        seats: [1, 2, 3].map((n) => ({
          id: `seat-${n}`,
          sectionId: section.id,
          rowLabel: "A",
          colNo: n,
          status: conflict && n === 1 ? "HELD" : "AVAILABLE",
        })),
      },
    }),
  );
  await page.route("**/api/v1/seat-holds", (route) => {
    posted = route.request().postDataJSON().seatIds;
    if (!conflict) {
      conflict = true;
      return route.fulfill({
        status: 409,
        json: { code: "SEAT_ALREADY_HELD", message: "already held" },
      });
    }
    return route.fulfill({
      json: { ...hold, expiresAt: new Date(Date.now() + 300000).toISOString() },
    });
  });
  await page.goto("/shows/show-1/schedule-1/seats");
  await page.getByRole("button", { name: "R석 A열 1번, 선택 가능" }).click();
  await page.getByRole("button", { name: "R석 A열 2번, 선택 가능" }).click();
  const action = page.getByRole("button", { name: "이 가격으로 좌석 확보" });
  if (info.project.name.startsWith("mobile")) {
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(action).toBeInViewport();
    const bar = await page.locator(".booking-action").boundingBox();
    expect(
      Math.abs(bar!.y + bar!.height - page.viewportSize()!.height),
    ).toBeLessThan(2);
    await expect(page.locator(".booking-action-summary")).toContainText(
      "2석 선택 · 예상 총액 180,000원",
    );
  }
  await action.click();
  await expect(
    page.getByRole("button", { name: "R석 A열 1번, 다른 사용자 확보" }),
  ).toBeDisabled();
  await expect(
    page.getByRole("button", { name: "R석 A열 2번, 선택됨" }),
  ).toHaveAttribute("aria-pressed", "true");
  await expect(
    page.getByRole("alert").filter({ hasText: "남은 선택을 확인" }),
  ).toBeVisible();
  await expect(
    page.getByText("SEAT_ALREADY_HELD", { exact: false }),
  ).toHaveCount(0);
  await noPageOverflow(page);
  await page.screenshot({
    path: info.outputPath("seat-conflict.png"),
    fullPage: true,
  });
  await action.click();
  await expect(page).toHaveURL("/checkout/hold-1");
  expect(posted).toEqual(["seat-2"]);
});

test("countdown warns once and expiry offers seat selection instead of payment", async ({
  page,
}, info) => {
  await page.clock.install();
  const requests = await mockApi(page);
  const deadline = new Date(Date.now() + 55000).toISOString();
  await page.route("**/api/v1/seat-holds/hold-1", (route) =>
    route.fulfill({ json: { ...hold, expiresAt: deadline } }),
  );
  await page.goto("/checkout/hold-1");
  await expect(page.getByRole("timer")).toBeVisible();
  await expect(page.getByRole("status")).toContainText("1분 이내");
  if (info.project.name.startsWith("mobile")) {
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(
      page.getByRole("button", { name: "180,000원 결제하기" }),
    ).toBeInViewport();
    await expect(page.locator(".booking-action-summary")).toContainText(
      "남은 시간",
    );
    await page.screenshot({ path: info.outputPath("checkout-action.png") });
  }
  await page.clock.fastForward(60000);
  await expect(page.getByText("좌석 확보 시간이 끝났어요.")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "180,000원 결제하기" }),
  ).toHaveCount(0);
  await page.getByRole("link", { name: /좌석 다시 선택하기/ }).click();
  await expect(page).toHaveURL("/shows/show-1/schedule-1/seats");
  expect(requests).toHaveLength(0);
});

test("pending payment keeps checking after countdown expires", async ({
  page,
}) => {
  await page.clock.install();
  const requests = await mockApi(page);
  const deadline = new Date(Date.now() + 5000).toISOString();
  await page.route("**/api/v1/seat-holds/hold-1", (route) =>
    route.fulfill({ json: { ...hold, expiresAt: deadline } }),
  );
  let approved = false;
  await page.route("**/api/v1/payments/payment-1", (route) =>
    route.fulfill({
      json: { ...payment, status: approved ? "APPROVED" : "PENDING" },
    }),
  );
  await page.goto("/checkout/hold-1");
  await page.getByRole("button", { name: "180,000원 결제하기" }).click();
  await expect(
    page.getByRole("button", { name: "결제 결과 확인 중…" }),
  ).toBeDisabled();
  await page.clock.fastForward(8000);
  await expect(page.getByText("좌석 확보 시간이 끝났어요.")).toHaveCount(0);
  await expect(
    page.getByRole("link", { name: /좌석 다시 선택하기/ }),
  ).toHaveCount(0);
  await expect(page.getByRole("timer")).toHaveCount(0);
  approved = true;
  await page.clock.fastForward(3000);
  await expect(
    page.getByRole("heading", { name: "그날의 밤, 당신의 자리가 준비됐어요." }),
  ).toBeVisible();
  expect(requests).toHaveLength(1);
});

test("sold hold recovers a pending payment across expiry and refresh", async ({
  page,
}) => {
  await page.clock.install();
  await mockApi(page);
  await page.route("**/api/v1/seat-holds/hold-1", (route) =>
    route.fulfill({
      json: {
        ...hold,
        status: "SOLD",
        expiresAt: new Date(Date.now() - 10000).toISOString(),
      },
    }),
  );
  let approved = false;
  await page.route("**/api/v1/payments", (route) =>
    route.fulfill({
      json: {
        payments: [
          { ...hold, ...payment, status: approved ? "APPROVED" : "PENDING" },
        ],
      },
    }),
  );
  await page.goto("/checkout/hold-1");
  await expect(
    page.getByRole("button", { name: "결제 결과 확인 중…" }),
  ).toBeDisabled();
  await expect(
    page.getByRole("link", { name: /좌석 다시 선택하기/ }),
  ).toHaveCount(0);
  approved = true;
  await page.clock.fastForward(4000);
  await expect(
    page.getByRole("heading", { name: "그날의 밤, 당신의 자리가 준비됐어요." }),
  ).toBeVisible();
});

test("first queue poll rate limit shows countdown and respects Retry-After", async ({ page }) => {
  await page.clock.install();
  await mockApi(page);
  let polls = 0;
  await page.route("**/api/v1/queue/enqueue", (route) =>
    route.fulfill({ json: { state: "WAITING", ticket: "ticket-1", scheduleId: schedule.id, position: 10, estimatedWaitSeconds: 60 } }),
  );
  await page.route("**/api/v1/queue/tickets/*", (route) => {
    polls++;
    return polls === 1
      ? route.fulfill({ status: 429, headers: { "Retry-After": "5" }, json: { code: "RATE_LIMIT_EXCEEDED" } })
      : route.fulfill({ json: { state: "WAITING", ticket: "ticket-1", scheduleId: schedule.id, position: 8, estimatedWaitSeconds: 60 } });
  });
  await page.goto("/shows/show-1/schedule-1/queue");
  await expect(page.getByText(/5초 뒤 자동 재개/)).toBeVisible();
  await page.clock.fastForward(4000);
  expect(polls).toBe(1);
  await page.clock.fastForward(1100);
  await expect(page.getByText("내 앞에", { exact: true })).toBeVisible();
  expect(polls).toBeGreaterThan(1);
});

for (const restored of [false, true]) {
  test(`payment result lookup failure recovers without another charge (${restored ? "restored" : "submitted"})`, async ({ page }, info) => {
    const requests = await mockApi(page);
    let offline = true;
    if (restored) {
      await page.route("**/api/v1/seat-holds/hold-1", (route) => route.fulfill({ json: { ...hold, status: "SOLD", expiresAt: new Date(Date.now() - 1000).toISOString() } }));
      await page.route("**/api/v1/payments", (route) => {
        if (route.request().method() !== "GET") return route.fallback();
        return offline
          ? route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } })
          : route.fulfill({ json: { payments: [{ ...hold, ...payment, status: "APPROVED" }] } });
      });
    } else {
      await page.route("**/api/v1/payments/payment-1", (route) => offline
        ? route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } })
        : route.fulfill({ json: payment }));
    }
    await page.goto("/checkout/hold-1");
    if (!restored) await page.getByRole("button", { name: "180,000원 결제하기" }).click();
    await expect(page.getByText("결제 결과 조회가 잠시 끊겼어요.")).toBeVisible();
    const retry = page.getByRole("button", { name: "결제 결과 다시 조회" });
    await expect(retry).toBeEnabled();
    await expect(page.getByRole("link", { name: /좌석 다시 선택하기/ })).toHaveCount(0);
    if (info.project.name.startsWith("mobile")) {
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(retry).toBeInViewport();
      await page.screenshot({ path: info.outputPath("payment-lookup-recovery.png") });
    }
    offline = false;
    await retry.click();
    await expect(page.getByRole("heading", { name: "그날의 밤, 당신의 자리가 준비됐어요." })).toBeVisible();
    expect(requests).toHaveLength(restored ? 0 : 1);
  });
}

test("seat refresh failure preserves the map and revalidates selected seats", async ({ page }) => {
  await mockApi(page);
  let offline = false;
  let seatOneSold = false;
  await page.route("**/api/v1/shows/*/schedules/*/seats", (route) => offline
    ? route.fulfill({ status: 503, json: { code: "UNAVAILABLE" } })
    : route.fulfill({ json: { seats: [1, 2].map((n) => ({ id: `seat-${n}`, sectionId: section.id, rowLabel: "A", colNo: n, status: seatOneSold && n === 1 ? "SOLD" : "AVAILABLE" })) } }));
  await page.goto("/shows/show-1/schedule-1/seats");
  const first = page.getByRole("button", { name: /R석 A열 1번,/ });
  const second = page.getByRole("button", { name: /R석 A열 2번,/ });
  await first.click();
  await expect(first).toHaveAttribute("aria-pressed", "true");
  await second.click();
  await expect(second).toHaveAttribute("aria-pressed", "true");
  offline = true;
  await expect(page.getByText("좌석 상태를 새로 확인하지 못했어요.")).toBeVisible({ timeout: 25000 });
  await expect(first).toHaveAttribute("aria-pressed", "true");
  await expect(first).toBeDisabled();
  await expect(second).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: /이 가격으로 좌석 확보/ })).toHaveCount(0);
  offline = false;
  seatOneSold = true;
  await page.getByRole("button", { name: "좌석 상태 다시 확인" }).click();
  await expect(first).toHaveAttribute("aria-pressed", "false");
  await expect(first).toBeDisabled();
  await expect(second).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: /이 가격으로 좌석 확보/ })).toBeEnabled();
});

test("missing section price offers recovery and reprices the retained selection", async ({ page }, info) => {
  await mockApi(page);
  let currentPrice: number | null = null;
  await page.route("**/api/v1/shows/*/schedules/*/pricing", (route) => route.fulfill({ json: { ticks: currentPrice === null ? [] : [{ tickId: "tick-1", scheduleId: schedule.id, sectionId: section.id, basePrice: 80000, currentPrice, occurredAt: new Date().toISOString() }] } }));
  await page.goto("/shows/show-1/schedule-1/seats");
  const seat = page.getByRole("button", { name: /R석 A열 1번,/ });
  await seat.click();
  const action = page.getByRole("region", { name: "예매 진행", exact: true });
  await expect(action.getByRole("button", { name: "가격 다시 확인" })).toBeEnabled();
  currentPrice = 95000;
  await action.getByRole("button", { name: "가격 다시 확인" }).click();
  await expect(action.getByRole("button", { name: /이 가격으로 좌석 확보/ })).toBeEnabled();
  await expect(seat).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("95,000원", { exact: true }).first()).toBeVisible();
  currentPrice = 100000;
  await expect(page.getByText("100,000원", { exact: true }).first()).toBeVisible({ timeout: 15000 });
  await expect(seat).toHaveAttribute("aria-pressed", "true");
  if (info.project.name.startsWith("mobile")) await expect(action).toContainText("1석 선택 · 예상 총액 100,000원");
});

for (const initial of [true, false]) {
  test(`live seat event survives a delayed ${initial ? "initial" : "refresh"} snapshot`, async ({ page }) => {
    await page.clock.install();
    await mockApi(page);
    let emit: ((type: string, scheduleId?: string) => void) | undefined;
    await page.routeWebSocket(/\/ws(?:\?|$)/, (socket) => {
      socket.onMessage((raw) => {
        const frame = String(raw);
        if (frame.startsWith("CONNECT\n") || frame.startsWith("STOMP\n")) {
          socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
        }
        if (frame.startsWith("SUBSCRIBE\n") && frame.includes("/topic/schedules/schedule-1/seats")) {
          const subscription = frame.match(/\nid:([^\n]+)/)?.[1];
          emit = (type, scheduleId = schedule.id) => {
            const body = JSON.stringify({ type, scheduleId, seatIds: ["seat-1"], occurredAt: new Date().toISOString() });
            socket.send(`MESSAGE\nsubscription:${subscription}\nmessage-id:${Date.now()}\ndestination:/topic/schedules/schedule-1/seats\ncontent-type:application/json\n\n${body}\0`);
          };
        }
      });
    });
    let reads = 0;
    let release: (() => void) | undefined;
    await page.route("**/api/v1/shows/*/schedules/*/seats", async (route) => {
      reads++;
      if (reads === (initial ? 1 : 2)) await new Promise<void>((resolve) => { release = resolve; });
      await route.fulfill({ json: { seats: [1, 2].map((n) => ({ id: `seat-${n}`, sectionId: section.id, rowLabel: "A", colNo: n, status: "AVAILABLE" })) } });
    });
    await page.goto("/shows/show-1/schedule-1/seats");
    await expect.poll(() => !!emit).toBe(true);
    const first = page.getByRole("button", { name: /R석 A열 1번,/ });
    const second = page.getByRole("button", { name: /R석 A열 2번,/ });
    if (!initial) {
      await first.click();
      await second.click();
      await page.clock.fastForward(15000);
    }
    await expect.poll(() => !!release).toBe(true);
    emit!("SEAT_HELD");
    if (!initial) await expect(first).toBeDisabled();
    // A round trip after the MESSAGE lets the browser process the frame before HTTP completes.
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())));
    release!();
    await expect(first).toBeDisabled();
    await expect(first).toHaveAttribute("aria-pressed", "false");
    if (!initial) await expect(second).toHaveAttribute("aria-pressed", "true");
    emit!("SEAT_RELEASED", "another-schedule");
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())));
    await expect(first).toBeDisabled();
    emit!("SEAT_RELEASED");
    await expect(first).toBeEnabled();
    await expect(first).toHaveAttribute("aria-pressed", "false");
    if (!initial) await expect(second).toHaveAttribute("aria-pressed", "true");
  });
}

test("reconnected subscriptions immediately refresh missed seats and price history", async ({ page }) => {
  await page.clock.install();
  await mockApi(page);
  let disconnected = false, connections = 0, seatReads = 0, historyReads = 0;
  let disconnect: (() => Promise<void>) | undefined;
  // Track active STOMP handles, not all SUBSCRIBE frames ever received.
  // PriceChart replaces its subscription when the initial section is resolved.
  const subscriptions: Map<string, string>[] = [];
  const activeDestinations = () => [...(subscriptions.at(-1)?.values() ?? [])];
  await page.route("**/api/v1/shows/*/schedules/*/seats", (route) => {
    seatReads++;
    return route.fulfill({ json: { seats: [{ id: "seat-1", sectionId: section.id, rowLabel: "A", colNo: 1, status: disconnected ? "HELD" : "AVAILABLE" }] } });
  });
  await page.route("**/api/v1/sections/*/pricing/history**", (route) => {
    historyReads++;
    return route.fulfill({ json: { ticks: [] } });
  });
  await page.routeWebSocket(/\/ws(?:\?|$)/, (socket) => {
    let index = -1;
    socket.onMessage((raw) => {
      const frame = String(raw);
      if (frame.startsWith("CONNECT\n") || frame.startsWith("STOMP\n")) {
        index = connections++;
        subscriptions[index] = new Map();
        socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
        disconnect = () => socket.close({ code: 1012, reason: "test reconnect" });
      }
      if (frame.startsWith("SUBSCRIBE\n")) {
        const id = frame.match(/\nid:([^\n]+)/)?.[1];
        const destination = frame.match(/\ndestination:([^\n]+)/)?.[1];
        if (id && destination) subscriptions[index]?.set(id, destination);
      }
      if (frame.startsWith("UNSUBSCRIBE\n")) {
        const id = frame.match(/\nid:([^\n]+)/)?.[1];
        if (id) subscriptions[index]?.delete(id);
      }
    });
  });
  await page.goto("/shows/show-1/schedule-1/seats");
  const seat = page.getByRole("button", { name: /R석 A열 1번,/ });
  await seat.click();
  await expect.poll(() => activeDestinations().filter(s => s.endsWith('/pricing')).length).toBe(1);
  await expect.poll(() => historyReads).toBeGreaterThan(0);
  const beforeSeats = seatReads, beforeHistory = historyReads, beforeConnections = connections;
  disconnected = true;
  await disconnect!();
  // Reconnect jitter is at most 12s, shorter than the 15s seat poll.
  await page.clock.fastForward(12000);
  await expect.poll(() => connections).toBeGreaterThan(beforeConnections);
  await expect.poll(() => seatReads).toBeGreaterThan(beforeSeats);
  await expect.poll(() => historyReads).toBeGreaterThan(beforeHistory);
  await expect(seat).toBeDisabled();
  await expect(seat).toHaveAttribute("aria-pressed", "false");
  expect(activeDestinations().filter(s => s.endsWith('/seats'))).toHaveLength(1);
  expect(activeDestinations().filter(s => s.endsWith('/pricing'))).toHaveLength(1);
});
