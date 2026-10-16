export const won = (amount: number) => `${amount.toLocaleString("ko-KR")}원`;
/** 읽을 수 없는 날짜(예: 서버에 남은 +99999년 같은 값)는 그대로 보여 준다 — 화면 전체가 RangeError 로 멈추지 않게. */
export function dateTime(iso: string) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}
