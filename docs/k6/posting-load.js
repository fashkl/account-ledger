import http from "k6/http";
import {check, sleep} from "k6";

http.setResponseCallback(http.expectedStatuses({min: 200, max: 499}));

export const options = {
    scenarios: {
        posting: {
            executor: "ramping-arrival-rate",
            startRate: 100,
            timeUnit: "1s",
            preAllocatedVUs: 80,
            maxVUs: 400,
            stages: [
                {target: 100, duration: "1m"},
                {target: 250, duration: "1m"},
                {target: 500, duration: "2m"}
            ]
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
        headers: {"Content-Type": "application/json", "X-Correlation-Id": `k6-${__VU}-${__ITER}`}
    });
    check(res, {"posting status is 2xx/4xx": (r) => r.status >= 200 && r.status < 500});
    sleep(0.1);
}
