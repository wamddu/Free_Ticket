import { api } from "./axios";
import type { ApiDataResponse } from "../types/Payment";

// Mirrors the backend QueueStatus. admitted: may order now. bypassed: the queue is down, order anyway.
export type QueueStatus = {
  admitted: boolean;
  bypassed: boolean;
  position: number | null;
  estimatedWaitSeconds: number | null;
  nextPollSeconds: number | null;
  passExpiresInSeconds: number | null;
  // Only from the edge waiting room: send it with the order (X-Queue-Token).
  token?: string | null;
};

export async function enterQueue(eventId: number) {
  const response = await api.post<ApiDataResponse<QueueStatus>>(`/queue/${eventId}`);
  return response.data.data;
}

export async function getQueueStatus(eventId: number) {
  const response = await api.get<ApiDataResponse<QueueStatus>>(`/queue/${eventId}`);
  return response.data.data;
}
