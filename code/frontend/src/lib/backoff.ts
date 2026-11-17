// 1s, 2s, 4s, 8s, then every 10s, plus up to 1s of jitter so a crowd does not retry in the same instant.
export function busyRetryDelayMs(attempt: number, random = Math.random) {
  return Math.min(1000 * 2 ** attempt, 10_000) + Math.floor(random() * 1000);
}
