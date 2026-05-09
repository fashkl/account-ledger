import http from "k6/http";
import {check, sleep} from "k6";

http.setResponseCallback(http.expectedStatuses({min: 200, max: 499}));

export const options = {
    scenarios: {
        spike_and_soak: {
            executor: "ramping-arrival-rate",
            startRate: 100,
            timeUnit: "1s",
            preAllocatedVUs: 80,
            maxVUs: 500,
            stages: [
                {target: 100, duration: "1m"},
                {target: 1200, duration: "30s"},
                {target: 1200, duration: "3m"},
                {target: 500, duration: "10m"},
                {target: 200, duration: "2m"}
            ]
        }
    },
    thresholds: {
        http_req_failed: ["rate<0.02"],
        http_req_duration: ["p(99)<80", "p(95)<35"],
        checks: ["rate>0.99"]
    }
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const CUSTOMER_ID = __ENV.CUSTOMER_ID || "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const CURRENCY = __ENV.CURRENCY || "AED";

export default function () {
    const res = http.get(`${BASE_URL}/api/v1/orders/buying-power/${CUSTOMER_ID}?currency=${CURRENCY}`, {
        headers: {"X-Correlation-Id": `spike-${__VU}-${__ITER}`}
    });
    check(res, {"status is 200/404": (r) => r.status === 200 || r.status === 404});
    sleep(0.01);
}
