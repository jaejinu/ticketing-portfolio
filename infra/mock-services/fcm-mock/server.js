/*
 * =============================================================================
 * FCM Mock — Firebase Cloud Messaging 흉내내기
 * -----------------------------------------------------------------------------
 * 실제 FCM HTTP v1 API 시그니처:
 *   POST https://fcm.googleapis.com/v1/projects/{projectId}/messages:send
 *   Body: { "message": { "token": "...", "notification": { ... } } }
 *
 * 이 mock은 같은 경로로 받아 즉시 200을 돌려준다.
 * 보낸 메시지는 메모리에 누적해 /messages 로 확인 가능.
 * =============================================================================
 */

const express = require('express');

const PORT = parseInt(process.env.PORT || '8086', 10);

const app = express();
app.use(express.json({ limit: '1mb' }));

// 최근 200개만 보관 — 디버깅 용도이므로 메모리 폭발 방지.
const recent = [];
const MAX_RECENT = 200;

// 헬스체크 — docker healthcheck가 쓴다.
app.get('/health', (_req, res) => {
  res.status(200).json({ status: 'ok', service: 'fcm-mock' });
});

// 실제 FCM HTTP v1 엔드포인트 모양 그대로.
// projectId가 뭐든 무조건 200으로 흉내내고, 메시지 ID는 가짜로 생성.
app.post('/v1/projects/:projectId/messages:send', (req, res) => {
  const projectId = req.params.projectId;
  const message = req.body?.message;

  // 실제 FCM 동작: message가 없으면 400.
  if (!message) {
    return res.status(400).json({
      error: {
        code: 400,
        message: 'Request contains an invalid argument.',
        status: 'INVALID_ARGUMENT',
      },
    });
  }

  // 토큰이 없거나 비어있으면 400 — 실제 FCM과 같은 행동.
  if (!message.token && !message.topic && !message.condition) {
    return res.status(400).json({
      error: {
        code: 400,
        message: 'One of "token", "topic", or "condition" must be specified.',
        status: 'INVALID_ARGUMENT',
      },
    });
  }

  // 가짜 메시지 ID — 실제 FCM 응답 포맷: "projects/{projectId}/messages/{id}"
  const fakeId = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  const name = `projects/${projectId}/messages/${fakeId}`;

  // 최근 메시지 기록.
  recent.push({ at: new Date().toISOString(), projectId, message, name });
  if (recent.length > MAX_RECENT) recent.shift();

  console.log(`[fcm-mock] sent → project=${projectId} token=${(message.token || '').slice(0, 12)}...`);
  res.status(200).json({ name });
});

// 디버그용 — 최근 푸시 확인.
app.get('/messages', (_req, res) => {
  res.json({ count: recent.length, messages: recent });
});

// 그 외 경로 — 친절한 404.
app.use((req, res) => {
  res.status(404).json({ error: 'not found', path: req.path });
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`[fcm-mock] listening on :${PORT}`);
});
