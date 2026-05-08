import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    reads: {
      executor: "constant-arrival-rate",
      rate: 200,
      timeUnit: "1s",
      duration: "2m",
      preAllocatedVUs: 30,
      maxVUs: 120
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(99)<10"]
  }
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const CUSTOMER_ID = __ENV.CUSTOMER_ID || "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

export default function () {
  const res = http.get(`${BASE_URL}/api/v1/orders/buying-power/${CUSTOMER_ID}?currency=AED`);
  check(res, { "read status is 200/404": (r) => r.status === 200 || r.status === 404 });
  sleep(0.05);
}
