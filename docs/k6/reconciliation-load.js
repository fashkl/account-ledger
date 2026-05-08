import http from "k6/http";
import { check, sleep } from "k6";

export const options = {
  scenarios: {
    recon: {
      executor: "constant-arrival-rate",
      rate: 2,
      timeUnit: "1m",
      duration: "30m",
      preAllocatedVUs: 2,
      maxVUs: 5
    }
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(99)<5000"]
  }
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export default function () {
  const res = http.post(`${BASE_URL}/api/v1/admin/jobs/reconciliation/run`, null, {
    headers: { "X-Correlation-Id": `recon-${__VU}-${__ITER}` }
  });
  check(res, { "recon status is 2xx/503": (r) => (r.status >= 200 && r.status < 300) || r.status === 503 });
  sleep(1);
}
