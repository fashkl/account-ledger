import http from "k6/http";
import {check, sleep} from "k6";

http.setResponseCallback(http.expectedStatuses({min: 200, max: 499}));

export const options = {
    scenarios: {
        contention: {
            executor: "ramping-arrival-rate",
            startRate: 50,
            timeUnit: "1s",
            preAllocatedVUs: 40,
            maxVUs: 260,
            stages: [
                {target: 50, duration: "1m"},
                {target: 150, duration: "2m"},
                {target: 300, duration: "2m"}
            ]
        }
    },
    thresholds: {
        http_req_failed: ["rate<0.03"],
        http_req_duration: ["p(99)<250", "p(95)<120"],
        checks: ["rate>0.99"]
    }
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const CUSTOMER_ID = __ENV.CUSTOMER_ID || "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const CURRENCY = __ENV.CURRENCY || "AED";

const SETTLED_CASH_ACCOUNT_ID = __ENV.SETTLED_CASH_ACCOUNT_ID || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const SETTLEMENT_PENDING_ACCOUNT_ID = __ENV.SETTLEMENT_PENDING_ACCOUNT_ID || "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
const BROKERAGE_OMNIBUS_ACCOUNT_ID = __ENV.BROKERAGE_OMNIBUS_ACCOUNT_ID || "ffffffff-ffff-ffff-ffff-ffffffffffff";

const HOTSPOT_WITHDRAWAL_ID = __ENV.HOTSPOT_WITHDRAWAL_ID || "99999999-9999-9999-9999-999999999999";

function deterministicUuid(prefix, vu, iter) {
    const seed = `${prefix}-${vu}-${iter}`;
    let hash = 0;
    for (let i = 0; i < seed.length; i++) {
        hash = (hash * 33 + seed.charCodeAt(i)) >>> 0;
    }
    const h = hash.toString(16).padStart(8, "0");
    const p1 = `${h}${h}`.slice(0, 8);
    const p2 = `${h}${h}`.slice(2, 6);
    const p3 = `4${`${h}${h}`.slice(5, 8)}`;
    const p4 = `b${`${h}${h}`.slice(1, 4)}`;
    const p5 = `${h}${h}${h}`.slice(0, 12);
    return `${p1}-${p2}-${p3}-${p4}-${p5}`;
}

export default function () {
    const eventId = deterministicUuid("hotspot", __VU, __ITER);
    const payload = JSON.stringify({
        eventType: "WITHDRAWAL_REQUESTED",
        eventId,
        withdrawalId: HOTSPOT_WITHDRAWAL_ID,
        customerId: CUSTOMER_ID,
        settledCashAccountId: SETTLED_CASH_ACCOUNT_ID,
        settlementPendingAccountId: SETTLEMENT_PENDING_ACCOUNT_ID,
        brokerageOmnibusAccountId: BROKERAGE_OMNIBUS_ACCOUNT_ID,
        amount: "1.00",
        currency: CURRENCY
    });

    const res = http.post(`${BASE_URL}/api/v1/cash/events`, payload, {
        headers: {"Content-Type": "application/json", "X-Correlation-Id": `hotspot-${__VU}-${__ITER}`}
    });
    check(res, {"status is 2xx/4xx": (r) => r.status >= 200 && r.status < 500});
    sleep(0.01);
}
