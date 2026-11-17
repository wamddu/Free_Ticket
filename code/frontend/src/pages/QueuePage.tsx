import axios from "axios";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";

import { createOrder } from "../api/orders";
import { enterQueue, getQueueStatus, type QueueStatus } from "../api/queue";

function errorCode(error: unknown) {
  return axios.isAxiosError(error) ? (error.response?.data?.code as string | undefined) : undefined;
}

function errorMessage(error: unknown) {
  return axios.isAxiosError(error) ? (error.response?.data?.message as string | undefined) : undefined;
}

function formatWait(seconds: number) {
  if (seconds < 60) return `${seconds}초`;
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest === 0 ? `${minutes}분` : `${minutes}분 ${rest}초`;
}

/**
 * Waiting room for events with a queue. Joins once, then asks again after the interval the server gives
 * (2s near the front, up to 30s at the back). When admitted it places the order chosen on the detail page.
 */
export default function QueuePage() {
  const navigate = useNavigate();
  const { eventId: rawEventId } = useParams<{ eventId: string }>();
  const [params] = useSearchParams();
  const eventId = Number(rawEventId);
  const ticketId = Number(params.get("ticketId"));
  const quantity = Number(params.get("quantity") ?? "1");
  const invalid = !Number.isInteger(eventId) || !Number.isInteger(ticketId) || ticketId < 1;
  const [status, setStatus] = useState<QueueStatus | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const error = invalid ? "예매할 티켓 정보가 없습니다. 공연 화면에서 다시 시도해 주세요." : failure;
  const [ordering, setOrdering] = useState(false);
  const rejoins = useRef(0);

  useEffect(() => {
    if (invalid) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const order = async (token?: string | null) => {
      setOrdering(true);
      try {
        const created = await createOrder(ticketId, quantity, token);
        if (active) navigate(`/payment/checkout/${created.orderId}`, { replace: true });
      } catch (orderError) {
        if (!active) return;
        // The pass expired between admission and ordering: line up again once.
        if (errorCode(orderError) === "QUEUE_REQUIRED" && rejoins.current < 1) {
          rejoins.current += 1;
          setOrdering(false);
          void step(enterQueue);
          return;
        }
        setFailure(errorMessage(orderError) ?? "주문을 생성하지 못했습니다.");
      }
    };

    const step = async (load: (id: number) => Promise<QueueStatus>) => {
      try {
        const next = await load(eventId);
        if (!active) return;
        setStatus(next);
        if (next.admitted) {
          await order(next.token);
          return;
        }
        timer = setTimeout(() => void step(getQueueStatus), (next.nextPollSeconds ?? 5) * 1000);
      } catch (queueError) {
        if (!active) return;
        // Not in line (yet), or the edge waiting room's pass ran out: join again.
        if (errorCode(queueError) === "QUEUE_NOT_JOINED" || errorCode(queueError) === "QUEUE_PASS_EXPIRED") {
          void step(enterQueue);
          return;
        }
        setFailure(errorMessage(queueError) ?? "대기열 정보를 불러오지 못했습니다.");
      }
    };

    void step(enterQueue);
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [invalid, eventId, ticketId, quantity, navigate]);

  return (
    <main className="mx-auto flex min-h-[500px] max-w-xl flex-col items-center justify-center px-4 py-16 text-center">
      <h1 className="text-2xl font-black text-gray-900">예매 대기 중</h1>
      {error ? (
        <>
          <p className="mt-6 font-bold text-red-500">{error}</p>
          <Link to={`/ticket/${rawEventId}`} className="mt-8 rounded-xl bg-[#453eda] px-6 py-3 font-bold text-white">
            공연 화면으로
          </Link>
        </>
      ) : ordering || status?.admitted ? (
        <p className="mt-6 text-lg font-bold text-[#453eda]">순서가 되었습니다. 주문을 만드는 중입니다...</p>
      ) : status?.position ? (
        <>
          <p className="mt-6 text-gray-600">내 앞의 대기 인원</p>
          <p className="mt-2 text-5xl font-black text-[#453eda]" aria-label="대기 순서">
            {(status.position - 1).toLocaleString()}명
          </p>
          <p className="mt-4 text-gray-600">예상 대기 시간 약 {formatWait(status.estimatedWaitSeconds ?? 0)}</p>
          <p className="mt-8 text-sm text-gray-400">
            이 화면을 닫거나 새로고침해도 순서는 유지됩니다. 순서가 되면 자동으로 결제 화면으로 이동합니다.
          </p>
        </>
      ) : (
        <p className="mt-6 text-gray-600">대기열에 들어가는 중입니다...</p>
      )}
    </main>
  );
}
