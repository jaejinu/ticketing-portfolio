import { SeatMap } from "@/components/seat-map/SeatMap";
import { BookingSteps } from "@/components/booking/BookingSteps";
import { PerformanceSummary } from "@/components/booking/PerformanceSummary";
export default async function SeatsPage({
  params,
}: {
  params: Promise<{ showId: string; scheduleId: string }>;
}) {
  const { showId, scheduleId } = await params;
  return (
    <div className="hybrid-container page-space space-y-6">
      <BookingSteps step={1} />
      <h1 className="page-title">마음에 드는 자리를 선택하세요.</h1>
      <PerformanceSummary showId={showId} scheduleId={scheduleId} />
      <SeatMap key={`${showId}:${scheduleId}`} showId={showId} scheduleId={scheduleId} />
    </div>
  );
}
