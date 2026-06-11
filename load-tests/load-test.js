/**
 * SwiftPay Load Test — K6
 * Target: 250 TPS sustained, 1 million total transactions
 *
 * Run:
 *   k6 run --out json=results.json load-test.js
 *
 * With PCAP capture (requires tcpdump + root):
 *   sudo tcpdump -i lo -w swiftpay-load.pcap port 8080 &
 *   k6 run --out json=results.json load-test.js
 *   sudo pkill tcpdump
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

// ── Custom metrics ─────────────────────────────────────────────────────────
const paymentSuccessCount  = new Counter("payment_success_total");
const paymentFailCount     = new Counter("payment_fail_total");
const insufficientFunds    = new Counter("payment_insufficient_funds_total");
const paymentSuccessRate   = new Rate("payment_success_rate");
const paymentLatency       = new Trend("payment_latency_ms", true);

// ── Test configuration ─────────────────────────────────────────────────────
export const options = {
  scenarios: {
    sustained_load: {
      executor: "constant-arrival-rate",
      rate: 250,           // 250 iterations/second = 250 TPS
      timeUnit: "1s",
      duration: "67m",     // 250 TPS × 67 min × 60 s ≈ 1,005,000 iterations
      preAllocatedVUs: 300,
      maxVUs: 500,
    },
  },

  thresholds: {
    // 95th percentile under 500ms
    http_req_duration:      ["p(95)<500"],
    // 99th percentile under 1s
    "http_req_duration{status:202}": ["p(99)<1000"],
    // At least 95% of requests succeed (202 or 200)
    payment_success_rate:   ["rate>0.95"],
    // Error rate below 5%
    http_req_failed:        ["rate<0.05"],
  },

  // HTTP connection settings
  httpDebug: "none",
  discardResponseBodies: false,
};

// ── Seeded accounts (must match Flyway V1 seed data) ──────────────────────
const ACCOUNTS = [
  "a0000000-0000-0000-0000-000000000001", // Alice  — $10,000
  "a0000000-0000-0000-0000-000000000002", // Bob    — $5,000
  "a0000000-0000-0000-0000-000000000003", // Carol  — $2,500
];

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

// ── Helpers ────────────────────────────────────────────────────────────────
function pickTwo(arr) {
  const i = Math.floor(Math.random() * arr.length);
  let j;
  do { j = Math.floor(Math.random() * arr.length); } while (j === i);
  return [arr[i], arr[j]];
}

function randomAmount() {
  // Small transfers to avoid draining accounts
  return (Math.random() * 9 + 1).toFixed(2); // $1.00 – $10.00
}

// ── Main VU function ───────────────────────────────────────────────────────
export default function () {
  const [senderId, receiverId] = pickTwo(ACCOUNTS);
  const idempotencyKey = uuidv4();
  const requestId      = uuidv4();

  const payload = JSON.stringify({
    sender_id:   senderId,
    receiver_id: receiverId,
    amount:      randomAmount(),
    currency:    "USD",
  });

  const params = {
    headers: {
      "Content-Type":    "application/json",
      "Idempotency-Key": idempotencyKey,
      "X-Request-ID":    requestId,
    },
    timeout: "10s",
  };

  const start = Date.now();
  const res   = http.post(`${BASE_URL}/v1/payments`, payload, params);
  const elapsed = Date.now() - start;

  paymentLatency.add(elapsed);

  const ok = check(res, {
    "status is 202 (accepted)":    (r) => r.status === 202,
    "status is 200 (idempotent)":  (r) => r.status === 202 || r.status === 200,
    "response has payment_id":     (r) => {
      try { return JSON.parse(r.body).payment_id !== undefined; }
      catch { return false; }
    },
  });

  if (res.status === 202 || res.status === 200) {
    paymentSuccessCount.add(1);
    paymentSuccessRate.add(true);
  } else if (res.status === 422) {
    insufficientFunds.add(1);
    paymentSuccessRate.add(false);
  } else {
    paymentFailCount.add(1);
    paymentSuccessRate.add(false);
    console.error(`Unexpected status=${res.status} body=${res.body?.substring(0, 200)}`);
  }
}

// ── Teardown summary ───────────────────────────────────────────────────────
export function handleSummary(data) {
  const summary = {
    totalRequests:    data.metrics.http_reqs?.values?.count       ?? 0,
    successCount:     data.metrics.payment_success_total?.values?.count ?? 0,
    failCount:        data.metrics.payment_fail_total?.values?.count    ?? 0,
    insufficientFunds: data.metrics.payment_insufficient_funds_total?.values?.count ?? 0,
    p50LatencyMs:     data.metrics.payment_latency_ms?.values?.["p(50)"] ?? 0,
    p95LatencyMs:     data.metrics.payment_latency_ms?.values?.["p(95)"] ?? 0,
    p99LatencyMs:     data.metrics.payment_latency_ms?.values?.["p(99)"] ?? 0,
    avgLatencyMs:     data.metrics.payment_latency_ms?.values?.avg       ?? 0,
    successRate:      (data.metrics.payment_success_rate?.values?.rate ?? 0) * 100,
    thresholdsPassed: !Object.values(data.metrics).some(
      (m) => m.thresholds && Object.values(m.thresholds).some((t) => t.ok === false)
    ),
  };

  console.log("\n════════════════════════════════════════");
  console.log("   SwiftPay Load Test Summary");
  console.log("════════════════════════════════════════");
  console.log(`Total requests  : ${summary.totalRequests.toLocaleString()}`);
  console.log(`Success         : ${summary.successCount.toLocaleString()} (${summary.successRate.toFixed(2)}%)`);
  console.log(`Failed          : ${summary.failCount.toLocaleString()}`);
  console.log(`Insufficient $$  : ${summary.insufficientFunds.toLocaleString()}`);
  console.log(`p50 latency     : ${summary.p50LatencyMs.toFixed(1)} ms`);
  console.log(`p95 latency     : ${summary.p95LatencyMs.toFixed(1)} ms`);
  console.log(`p99 latency     : ${summary.p99LatencyMs.toFixed(1)} ms`);
  console.log(`Avg latency     : ${summary.avgLatencyMs.toFixed(1)} ms`);
  console.log(`All thresholds  : ${summary.thresholdsPassed ? "✅ PASSED" : "❌ FAILED"}`);
  console.log("════════════════════════════════════════\n");

  return {
    "load-test-summary.json": JSON.stringify(summary, null, 2),
    stdout: " ",
  };
}
