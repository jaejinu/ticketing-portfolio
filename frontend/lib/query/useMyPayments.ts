"use client";

import { useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { listMyPayments } from "@/lib/api/payments";
import { subscribe } from "@/lib/stomp/client";

/** 목록과 상세가 결제·환불 이벤트 및 재연결 복구를 함께 처리한다. */
export function useMyPayments() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["my-payments"],
    queryFn: listMyPayments,
    staleTime: 0,
    refetchInterval: (q) =>
      q.state.data?.some((p) => p.status === "PENDING" || p.refundPending) ? 3000 : 10000,
  });
  useEffect(() => {
    const refresh = () => void client.invalidateQueries({ queryKey: ["my-payments"] });
    const sub = subscribe("/user/queue/payments", refresh, refresh);
    return () => sub.unsubscribe();
  }, [client]);
  return query;
}
