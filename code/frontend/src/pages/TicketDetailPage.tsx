import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import axios from "axios";
import NaverMap from "../components/NaverMap";
import BuyerTicketPriceHistory from "../components/BuyerTicketPriceHistory";

import { getEventDetail } from "../api/events";
import { createOrder } from "../api/orders";
import { useAuth } from "../context/AuthContext";
import {
  eventCategoryLabels,
  type EventDetail,
  type TicketOption,
} from "../types/Event";

function formatDate(value: string) {
  return value.replaceAll("-", ".");
}

function formatPeriod(startDate: string, endDate: string) {
  const start = formatDate(startDate);
  const end = formatDate(endDate);
  return start === end ? start : `${start} ~ ${end}`;
}

function formatDateTime(value: string) {
  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export default function TicketDetailPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const { id } = useParams<{ id: string }>();
  const eventId = Number(id);
  const [event, setEvent] = useState<EventDetail | null>(null);
  const [ticketOptions, setTicketOptions] = useState<TicketOption[]>([]);
  const [selectedTicketId, setSelectedTicketId] = useState<number | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [loading, setLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [ordering, setOrdering] = useState(false);

  useEffect(() => {
    let active = true;

    const loadDetail = async () => {
      if (!Number.isInteger(eventId) || eventId < 1) {
        setErrorMessage("올바른 이벤트 ID가 아닙니다.");
        setLoading(false);
        return;
      }

      setLoading(true);
      setErrorMessage(null);
      setSelectedTicketId(null);
      setQuantity(1);

      try {
        const data = await getEventDetail(eventId);

        if (active) {
          setEvent(data.event);
          setTicketOptions(data.ticketOptions);
          const firstAvailableTicket = data.ticketOptions.find(
            (ticket) => ticket.bookingAvailable,
          );
          setSelectedTicketId(firstAvailableTicket?.ticketId ?? null);
        }
      } catch (error) {
        if (active) {
          const message = axios.isAxiosError(error)
            ? error.response?.data?.message
            : null;
          setErrorMessage(message ?? "이벤트 정보를 불러오지 못했습니다.");
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };

    void loadDetail();

    return () => {
      active = false;
    };
  }, [eventId]);

  const selectedTicket = useMemo(
    () => ticketOptions.find((ticket) => ticket.ticketId === selectedTicketId),
    [selectedTicketId, ticketOptions],
  );

  const handleOrder = async () => {
    if (!selectedTicket) return;
    if (!user) {
      navigate("/login", { state: { from: `/ticket/${eventId}` } });
      return;
    }

    if (event?.queueEnabled) {
      navigate(`/queue/${eventId}?ticketId=${selectedTicket.ticketId}&quantity=${quantity}`);
      return;
    }

    setOrdering(true);
    setOrderError(null);
    try {
      const order = await createOrder(selectedTicket.ticketId, quantity);
      navigate(`/payment/checkout/${order.orderId}`);
    } catch (error) {
      const message = axios.isAxiosError(error)
        ? error.response?.data?.message
        : null;
      setOrderError(message ?? "주문을 생성하지 못했습니다.");
      setOrdering(false);
    }
  };

  if (loading) {
    return (
      <main className="flex min-h-[500px] items-center justify-center text-gray-500">
        이벤트 정보를 불러오는 중입니다.
      </main>
    );
  }

  if (errorMessage || !event) {
    return (
      <main className="flex min-h-[500px] items-center justify-center px-6 text-center">
        <p className="text-xl font-bold text-gray-500">
          {errorMessage ?? "공연 정보를 찾을 수 없습니다."}
        </p>
      </main>
    );
  }

  return (
    <main className="min-h-screen bg-[#f8f8f8] px-6 py-14">
      <div className="mx-auto max-w-6xl">
        <div className="rounded-2xl bg-white p-6 shadow-sm md:p-10">
          <div className="grid gap-12 md:grid-cols-[340px_1fr]">
            <div>
              <img
                src={event.mainImageUrl}
                alt={`${event.name} 포스터`}
                className="w-full rounded-xl object-cover"
              />
              <section className="mt-8">
                <h2 className="mb-4 text-lg font-black text-gray-900">
                  공연장 위치
                </h2>
                <NaverMap address={event.location} />
              </section>
            </div>

            <section className="min-w-0">
              <span className="text-sm font-bold text-[#453eda]">
                {eventCategoryLabels[event.category]}
              </span>
              <h1 className="mt-3 text-4xl font-black text-gray-900">
                {event.name}
              </h1>

              <div className="mt-8 border-y border-gray-200 py-6">
                <dl className="grid grid-cols-[100px_1fr] gap-y-5 text-sm">
                  <dt className="font-bold text-gray-700">공연 장소</dt>
                  <dd className="text-gray-600">{event.location}</dd>
                  <dt className="font-bold text-gray-700">공연 기간</dt>
                  <dd className="text-gray-600">
                    {formatPeriod(event.startDate, event.endDate)}
                  </dd>
                  <dt className="font-bold text-gray-700">관람 시간</dt>
                  <dd className="text-gray-600">{event.runningTime}분</dd>
                  <dt className="font-bold text-gray-700">공연 소개</dt>
                  <dd className="leading-6 text-gray-600">{event.description}</dd>
                </dl>
              </div>
              <div className="mt-8">
                <h2 className="text-lg font-black text-gray-900">티켓 선택</h2>
                {ticketOptions.length === 0 ? (
                  <p className="mt-4 rounded-xl bg-gray-50 px-5 py-8 text-center text-sm text-gray-500">
                    현재 판매 중인 티켓이 없습니다.
                  </p>
                ) : (
                  <div className="mt-4 space-y-3">
                    {ticketOptions.map((ticket) => {
                      const disabled = !ticket.bookingAvailable;
                      const selected = ticket.ticketId === selectedTicketId;

                      return (
                        <button
                          key={ticket.ticketId}
                          type="button"
                          disabled={disabled}
                          onClick={() => {
                            setSelectedTicketId(ticket.ticketId);
                            setQuantity(1);
                          }}
                          className={`w-full rounded-xl border p-4 text-left transition-colors ${
                            selected
                              ? "border-[#453eda] bg-[#f3f1ff]"
                              : "border-gray-200 hover:border-gray-400"
                          } disabled:cursor-not-allowed disabled:bg-gray-50 disabled:opacity-60`}
                        >
                          <span className="flex items-start justify-between gap-4">
                            <span>
                              <strong className="block text-sm">{ticket.type}</strong>
                              <span className="mt-2 block text-xs text-gray-500">
                                {formatDateTime(ticket.startTime)} · 잔여 {ticket.remainingTicket}매
                              </span>
                            </span>
                            <strong className="shrink-0">
                              {ticket.price.toLocaleString()}원
                            </strong>
                          </span>
                          {disabled && (
                            <span className="mt-2 block text-xs font-bold text-red-500">
                              {ticket.soldOut ? "매진" : "예매 마감"}
                            </span>
                          )}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>

              {selectedTicket && (
                <BuyerTicketPriceHistory eventId={eventId} ticketId={selectedTicket.ticketId} ticketType={selectedTicket.type} />
              )}

              {selectedTicket && (
                <div className="mt-8 flex flex-col gap-5 border-t border-gray-200 pt-6 sm:flex-row sm:items-end sm:justify-between">
                  <div>
                    <p className="text-sm text-gray-500">구매 수량</p>
                    <div className="mt-2 flex items-center gap-4">
                      <button
                        type="button"
                        disabled={quantity === 1}
                        onClick={() => setQuantity((value) => Math.max(1, value - 1))}
                        className="h-9 w-9 rounded-full border disabled:opacity-30"
                      >
                        −
                      </button>
                      <strong>{quantity}</strong>
                      <button
                        type="button"
                        disabled={quantity >= Math.min(selectedTicket.remainingTicket, 10)}
                        onClick={() => setQuantity((value) => value + 1)}
                        className="h-9 w-9 rounded-full border disabled:opacity-30"
                      >
                        +
                      </button>
                    </div>
                    <p className="mt-4 text-3xl font-black text-gray-900">
                      {(selectedTicket.price * quantity).toLocaleString()}원
                    </p>
                  </div>

                  <button
                    type="button"
                    disabled={ordering}
                    onClick={() => void handleOrder()}
                    className="rounded-xl bg-[#453eda] px-10 py-4 font-bold text-white transition-colors hover:bg-[#352fc0] disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {ordering ? "주문 생성 중..." : "예매하기"}
                  </button>
                </div>
              )}
              {event?.queueEnabled && (
                <p className="mt-4 text-right text-sm text-gray-500">
                  예매가 몰리는 공연이라 대기열 순서대로 예매합니다.
                </p>
              )}
              {orderError && (
                <p className="mt-4 text-right text-sm font-bold text-red-500">{orderError}</p>
              )}
            </section>
          </div>

          {event.descriptionImageUrl && (
            <section className="mt-16 border-t border-gray-200 pt-12">
              <h2 className="mb-8 text-2xl font-black">상세 정보</h2>
              <img
                src={event.descriptionImageUrl}
                alt={`${event.name} 상세 정보`}
                className="mx-auto max-w-full"
              />
            </section>
          )}
        </div>
      </div>
    </main>
  );
}
