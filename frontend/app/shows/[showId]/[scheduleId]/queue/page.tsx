import Link from "next/link";
import { QueueProgress } from "@/components/queue/QueueProgress";
import { BookingSteps } from "@/components/booking/BookingSteps";
import { PerformanceSummary } from "@/components/booking/PerformanceSummary";
export default async function QueuePage({
  params,
}: {
  params: Promise<{ showId: string; scheduleId: string }>;
}) {
  const { showId, scheduleId } = await params;
  return (
    <div className="hybrid-container page-space">
      <BookingSteps step={0} />
      <div className="mx-auto max-w-3xl space-y-8">
        <h1 className="page-title">곧, 당신의 차례입니다.</h1>
        <PerformanceSummary showId={showId} scheduleId={scheduleId} />
        <QueueProgress showId={showId} scheduleId={scheduleId} />
        <h2 className="text-xl font-semibold">
          차례가 되면 자동으로 이동해요.
        </h2>
        <p className="muted">
          이 화면을 열어 두세요. 대기 중에는 좌석과 가격이 확보되지 않습니다.
        </p>
        <Link
          href={`/shows/${showId}`}
          className="inline-block py-3 text-sm muted"
        >
          공연으로 돌아가기
        </Link>
      </div>
    </div>
  );
}
