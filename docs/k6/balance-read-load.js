import http from "k6/http";
import {check, sleep} from "k6";

http.setResponseCallback(http.expectedStatuses({min: 200, max: 404}));

export const options = {
    scenarios: {
        reads: {
            executor: "ramping-arrival-rate",
            startRate: 100,
            timeUnit: "1s",
            preAllocatedVUs: 60,
            maxVUs: 220,
            stages: [
                {target: 100, duration: "1m"},
                {target: 250, duration: "1m"},
                {target: 500, duration: "2m"}
            ]
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
    check(res, {"read status is 200/404": (r) => r.status === 200 || r.status === 404});
    sleep(0.05);
}
