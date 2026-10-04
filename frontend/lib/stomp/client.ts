/**
 * 이 파일의 책임: STOMP over WebSocket 클라이언트.
 *
 * - 백엔드 module-ws-bridge 가 Kafka 토픽 → STOMP 토픽 팬아웃을 담당한다.
 * - 프론트는 단일 long-lived 연결로 좌석/가격/대기열/알람 토픽을 다 구독.
 *
 * 재연결 정책:
 *
 *  연결 단절 감지
 *      |
 *      v
 *  reconnectDelay = 10s 고정 백오프 (jitter 약간)
 *      |
 *      v
 *  서버 복구 시 재구독 (Client 가 subscribe 콜백을 다시 호출)
 *
 *  왜 지수 백오프가 아닌 10초 고정?
 *  - 백엔드가 평시 5초 안에 복구된다고 가정. 10초면 thundering herd 도 적당히 분산.
 *  - 모바일 백그라운드 복귀 시 즉시 재연결되도록 reconnectDelay 가 최대치.
 *  - 운영자 대시보드에서 disconnect 패턴이 보이면 그때 정책 조정.
 *
 * 사용 예 (Phase 3):
 *   const stomp = getStompClient();
 *   const sub = stomp.subscribe('/topic/price/123/456', (msg) => ...);
 *   // 컴포넌트 unmount 시 sub.unsubscribe()
 */

'use client';

import { Client, type IFrame, type StompSubscription } from '@stomp/stompjs';

const WS_BASE =
  process.env.NEXT_PUBLIC_WS_BASE ?? 'ws://localhost:8088/ws';

// 10초 ± 2초 jitter. herd 회피용.
function reconnectDelayWithJitter(): number {
  const base = 10_000;
  const jitter = Math.floor(Math.random() * 4_000) - 2_000;
  return base + jitter;
}

let client: Client | null = null;

/**
 * 마지막으로 알려진 access token.
 *
 * 이전 구현의 버그(수정 이유): setStompAuthToken 이 "client 가 이미 있을 때만" 헤더를
 * 갱신해서, 로그인 → (client 아직 없음, no-op) → 페이지에서 첫 subscribe → client 생성
 * 순서면 CONNECT 가 항상 무토큰으로 나가 서버가 즉시 끊었다. 재연결도 계속 무토큰이라
 * 실시간 갱신이 영영 붙지 않았다. 토큰을 모듈 변수로 보관하고 beforeConnect 마다
 * 최신 값을 실어 순서 문제를 제거한다.
 */
let latestToken: string | null = null;

/* ────────────────────────────────────────────────────────
 * 구독 레지스트리
 * ────────────────────────────────────────────────────────
 * stomp.js 의 subscribe 핸들은 "현재 연결" 에 묶인다 — 재연결되면 무효.
 * 그래서 원하는 구독을 레지스트리에 등록해두고, onConnect(최초/재연결 공통) 때마다
 * 전부 다시 subscribe 한다.
 *
 * 이전 구현의 버그(수정 이유):
 *   - 연결 전 subscribe() 가 c.onConnect 를 "덮어써서" 마지막 1개 구독만 살아남았다.
 *     좌석 페이지처럼 SeatMap + PriceChart 가 동시에 구독하면 하나가 조용히 죽는다.
 *   - 반환값이 항상 null 이라 호출부가 unsubscribe 할 수 없어 리마운트마다 중복 누적.
 */
interface SubEntry {
  destination: string;
  onMessage: (body: string, headers: Record<string, string>) => void;
  /** 현재 연결에서 활성화된 stomp.js 핸들. 재연결되면 onConnect 가 갈아끼운다. */
  active: StompSubscription | null;
  onReconnect?: () => void;
}

const registry = new Set<SubEntry>();

/** 호출부에 돌려주는 핸들 — stomp.js 의 StompSubscription 과 같은 모양(unsubscribe). */
export interface SubscriptionHandle {
  unsubscribe: () => void;
}

/**
 * Lazy singleton. SSR 환경에서 window 가 없을 때 호출되지 않도록
 * 반드시 클라이언트 코드 (use client, useEffect, 이벤트 핸들러 등) 에서만 호출.
 */
export function getStompClient(): Client {
  if (typeof window === 'undefined') {
    // SSR 에서 실수로 호출되면 dev 서버 프로세스가 WS 재연결 루프를 돌며 리소스를 누수한다.
    // 조용히 넘어가지 않고 즉시 실패시켜 호출부 버그를 드러낸다.
    throw new Error('getStompClient() 는 브라우저에서만 호출할 수 있습니다 (useEffect 안에서 사용).');
  }
  if (client) return client;

  const c = new Client({
    brokerURL: (() => {
      const url = new URL(WS_BASE, window.location.href);
      if (url.protocol === 'http:') url.protocol = 'ws:';
      if (url.protocol === 'https:') url.protocol = 'wss:';
      return url.href;
    })(),
    // 인증은 connectHeaders 로 access token 을 전달.
    // 최초 연결/재연결 직전(beforeConnect)마다 최신 토큰을 다시 실으므로
    // 생성 시점의 값은 초기값일 뿐이다.
    connectHeaders: latestToken ? { Authorization: `Bearer ${latestToken}` } : {},
    beforeConnect: () => {
      if (client !== c) return;
      c.connectHeaders = latestToken
        ? { Authorization: `Bearer ${latestToken}` }
        : {};
    },
    // 빈 함수면 stomp.js 가 콘솔에 디버그 로그를 뱉지 않음. 개발 모드에서만 켠다.
    debug:
      process.env.NODE_ENV === 'development'
        ? (msg) => console.debug('[stomp]', msg)
        : () => {},
    // 단절 시 재연결 간격. stomp.js 가 자동으로 호출.
    reconnectDelay: reconnectDelayWithJitter(),
    // heartbeat (ms). 서버와 합의된 값으로 Phase 3 에서 맞춤.
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
  });
  client = c;

  c.onStompError = (frame: IFrame) => {
    if (client !== c) return;
    // 인증 만료/규칙 위반 등 서버가 명시적으로 에러 프레임을 보냈을 때.
    console.warn('[stomp] error frame', frame.headers, frame.body);
  };

  // 최초 연결 + 모든 재연결에서 레지스트리 전체를 (다시) 구독한다.
  let connectedBefore = false;
  c.onConnect = () => {
    if (client !== c) return;
    for (const entry of registry) {
      entry.active = c.subscribe(entry.destination, (msg) => {
        if (client === c && registry.has(entry))
          entry.onMessage(msg.body, msg.headers as Record<string, string>);
      });
    }
    if (connectedBefore) for (const entry of registry) entry.onReconnect?.();
    connectedBefore = true;
  };

  c.activate();
  return c;
}

/** 인증 세션 종료 시 연결·구독을 폐기한다. 컴포넌트 정리는 unsubscribe를 사용한다. */
export async function deactivateStompClient(): Promise<void> {
  const previous = client;
  // Detach synchronously: an old async shutdown must not clear a newer client.
  client = null;
  latestToken = null;
  for (const entry of registry) entry.active = null;
  registry.clear();
  await previous?.deactivate();
}

/**
 * access token 갱신 시 호출. 다음 재연결부터 새 토큰이 적용된다.
 * (이미 연결된 세션은 백엔드가 폐기하거나 다음 heartbeat 라운드에서 갱신.)
 */
export function setStompAuthToken(token: string | null): void {
  latestToken = token;
  // 이미 client 가 있으면 다음 재연결에 반영. 아직 없으면 생성 시 latestToken 이 쓰인다.
  if (client) {
    client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    // 페이지 첫 로드 직후엔 토큰 도착 전에 CONNECT 가 나가 거부될 수 있다.
    // 그 상태로 10초 재연결 주기를 기다리지 말고, 토큰이 생긴 즉시 재시도해
    // 실시간 스트림이 수 초 안에 붙게 한다. (이미 연결돼 있으면 건드리지 않음)
    if (token && client.active && !client.connected) {
      const reconnecting = client;
      void reconnecting.deactivate().then(() => {
        if (client === reconnecting && latestToken) reconnecting.activate();
      });
    }
  }
}

/**
 * 편의 헬퍼 — 구독을 레지스트리에 등록하고 핸들을 돌려준다.
 *
 * - 아직 연결 전이면 onConnect 시점에, 이미 연결됐으면 즉시 구독된다.
 * - 재연결 시에도 레지스트리 기반으로 자동 복구된다.
 * - onReconnect는 재구독 후 실행되어 연결 단절 중 누락된 데이터를 다시 조회한다.
 * - 반환 핸들의 unsubscribe() 는 레지스트리 제거 + 활성 구독 해제까지 책임진다
 *   (컴포넌트 unmount cleanup 에서 호출).
 */
export function subscribe(
  destination: string,
  onMessage: (body: string, headers: Record<string, string>) => void,
  onReconnect?: () => void,
): SubscriptionHandle {
  const c = getStompClient();
  const entry: SubEntry = { destination, onMessage, active: null, onReconnect };
  registry.add(entry);

  if (c.connected) {
    entry.active = c.subscribe(destination, (msg) => {
      if (client === c && registry.has(entry))
        onMessage(msg.body, msg.headers as Record<string, string>);
    });
  }

  return {
    unsubscribe: () => {
      registry.delete(entry);
      try {
        entry.active?.unsubscribe();
      } catch {
        // 이미 끊긴 연결의 핸들 해제 시도는 무해 — 조용히 무시.
      }
      entry.active = null;
    },
  };
}
