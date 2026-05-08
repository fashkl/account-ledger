import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    posting: {
      executor: "constant-arrival-rate",
      rate: 50,
      timeUnit: "1s",
      duration: "2m",
      preAllocatedVUs: 20,
      maxVUs: 100
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(99)<50"]
  }
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export default function () {
  const payload = JSON.stringify({
    idempotencyKey: `load-${__VU}-${__ITER}`,
    eventType: "ORDER_CREATED",
    referenceId: "11111111-1111-1111-1111-111111111111",
    effectiveDate: "2026-05-08",
    legs: []
  });
  const res = http.post(`${BASE_URL}/api/v1/ledger/postings`, payload, {
    headers: { "Content-Type": "application/json", "X-Correlation-Id": `k6-${__VU}-${__ITER}` }
  });
  check(res, { "posting status is 2xx/4xx": (r) => r.status >= 200 && r.status < 500 });
  sleep(0.1);
}
