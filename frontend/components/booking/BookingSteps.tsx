export function BookingSteps({ step }: { step: 0 | 1 | 2 | 3 }) {
  return (
    <nav aria-label="예매 단계">
      <ol className="booking-steps">
        {["대기", "좌석 선택", "결제", "완료"].map((label, i) => (
          <li key={label} aria-current={i === step ? "step" : undefined}>
            <span aria-hidden>{i < step ? "✓" : i + 1}</span> {label}
            <span className="sr-only">{i < step ? " 완료" : ""}</span>
          </li>
        ))}
      </ol>
    </nav>
  );
}
