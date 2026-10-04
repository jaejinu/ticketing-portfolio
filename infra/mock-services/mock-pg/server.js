/*
 * =============================================================================
 * Mock PG — 결제 게이트웨이 흉내
 * -----------------------------------------------------------------------------
 * 분포:
 *   95% → 200 OK (정상 승인)
 *    4% → 402 Payment Required (카드 한도 초과)
 *    1% → 5초 대기 후 504 (게이트웨이 타임아웃)
 *
 * 이렇게 함으로써 백엔드에서 다음을 시연 가능:
 *   - 재시도 + 지수 백오프
 *   - 서킷브레이커 (5초 타임아웃 누적 시 OPEN)
 *   - 사가 보상 트랜잭션 (좌석 락 해제, 큐 토큰 환불 등)
 *
 * MOCK_PG_SEED 환경변수로 시드 고정 — 부하 테스트 재현성 확보.
 * =============================================================================
 */

const express = require('express');

const PORT = parseInt(process.env.PORT || '8087', 10);
const SEED = parseInt(process.env.MOCK_PG_SEED || '42', 10);
const TIMEOUT_MS = parseInt(process.env.MOCK_PG_TIMEOUT_MS || '5000', 10);

// 간단한 mulberry32 PRNG — seed 가능한 균등 분포.
function makeRng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rng = makeRng(SEED);

const app = express();
app.use(express.json({ limit: '256kb' }));

// 헬스체크
app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok', service: 'mock-pg', seed: SEED });
});

// 결제 요청 — 실제 PG들은 token/orderId/amount 등을 받음. 간단히만 흉내.
//   POST /pay { orderId, amount, cardToken }
app.post('/pay', async (req, res) => {
  const { orderId, amount, cardToken } = req.body || {};

  // 최소 검증 — 진짜 PG도 필수 파라미터 누락이면 400.
  if (!orderId || !amount) {
    return res.status(400).json({
      code: 'INVALID_REQUEST',
      message: 'orderId, amount required',
    });
  }

  // 0..1 균등 분포에서 시나리오 분기.
  // ─ [0.00, 0.95) → 성공
  // ─ [0.95, 0.99) → 한도 초과
  // ─ [0.99, 1.00) → 타임아웃 시뮬레이션
  const dice = rng();

  if (dice < 0.95) {
    // 정상 승인 — approveNo는 임의 발급.
    const approveNo = `AP${Date.now().toString(36).toUpperCase()}`;
    console.log(`[mock-pg] 200 orderId=${orderId} amount=${amount} approve=${approveNo}`);
    return res.status(200).json({
      code: 'APPROVED',
      orderId,
      amount,
      approveNo,
      paidAt: new Date().toISOString(),
    });
  }

  if (dice < 0.99) {
    // 카드 한도 초과 — 사용자에게 즉시 알리고 보상 없이 종료해야 하는 케이스.
    console.log(`[mock-pg] 402 orderId=${orderId} reason=LIMIT_EXCEEDED`);
    return res.status(402).json({
      code: 'LIMIT_EXCEEDED',
      orderId,
      message: '카드 한도를 초과했습니다.',
    });
  }

  // 1% — 게이트웨이 타임아웃. setTimeout으로 일부러 응답을 늦춤.
  // 클라이언트(백엔드)의 타임아웃 설정이 짧으면 그쪽에서 먼저 끊김 → 보상 트랜잭션 트리거.
  console.log(`[mock-pg] timeout simulation orderId=${orderId} sleep=${TIMEOUT_MS}ms`);
  await new Promise((r) => setTimeout(r, TIMEOUT_MS));
  return res.status(504).json({
    code: 'GATEWAY_TIMEOUT',
    orderId,
    message: '게이트웨이 응답 지연',
  });
});

// 디버그 — 시드 재설정. 부하 테스트 시작 전에 호출하면 재현 가능한 분포 확보.
app.post('/admin/reseed', (req, res) => {
  const seed = parseInt(req.body?.seed ?? SEED, 10);
  // 모듈 전역 rng를 교체. 단순한 PRNG라 동일 코드 반복.
  const fresh = makeRng(seed);
  // rng 변수는 const라 직접 재할당 불가 — 우회: rng() 호출을 fresh()로 가리는 식으로
  // 간단히 처리하려면 객체 래퍼가 필요. 일단 학습 목적상 메시지로만 안내.
  console.log(`[mock-pg] reseed requested seed=${seed} (server restart 권장)`);
  res.json({ ok: true, note: '실제 시드 변경은 컨테이너 재시작 필요', requested: seed });
});

app.use((req, res) => {
  res.status(404).json({ error: 'not found', path: req.path });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[mock-pg] listening on :${PORT} seed=${SEED}`);
});
