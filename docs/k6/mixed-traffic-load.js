import http from "k6/http";
import {check, sleep} from "k6";

http.setResponseCallback(http.expectedStatuses({min: 200, max: 499}));

export const options = {
    scenarios: {
        buying_power_reads: {
            executor: "ramping-arrival-rate",
            exec: "readBuyingPower",
            startRate: 60,
            timeUnit: "1s",
            preAllocatedVUs: 40,
            maxVUs: 220,
            stages: [
                {target: 60, duration: "1m"},
                {target: 120, duration: "2m"},
                {target: 200, duration: "2m"}
            ]
        },
        order_events: {
            executor: "ramping-arrival-rate",
            exec: "postOrderEvent",
            startRate: 20,
            timeUnit: "1s",
            preAllocatedVUs: 20,
            maxVUs: 120,
            stages: [
                {target: 20, duration: "1m"},
                {target: 50, duration: "2m"},
                {target: 100, duration: "2m"}
            ]
        },
        cash_events: {
            executor: "ramping-arrival-rate",
            exec: "postCashEvent",
            startRate: 20,
            timeUnit: "1s",
            preAllocatedVUs: 20,
            maxVUs: 120,
            stages: [
                {target: 20, duration: "1m"},
                {target: 40, duration: "2m"},
                {target: 80, duration: "2m"}
            ]
        }
    },
    thresholds: {
        http_req_failed: ["rate<0.02"],
        http_req_duration: ["p(99)<120"],
        checks: ["rate>0.99"]
    }
};

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const CUSTOMER_ID = __ENV.CUSTOMER_ID || "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const CURRENCY = __ENV.CURRENCY || "AED";

const SETTLED_CASH_ACCOUNT_ID = __ENV.SETTLED_CASH_ACCOUNT_ID || "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const RESERVED_CASH_ACCOUNT_ID = __ENV.RESERVED_CASH_ACCOUNT_ID || "cccccccc-cccc-cccc-cccc-cccccccccccc";
const UNSETTLED_CASH_BUYS_ACCOUNT_ID = __ENV.UNSETTLED_CASH_BUYS_ACCOUNT_ID || "dddddddd-dddd-dddd-dddd-dddddddddddd";
const SETTLEMENT_PENDING_ACCOUNT_ID = __ENV.SETTLEMENT_PENDING_ACCOUNT_ID || "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
const BROKERAGE_OMNIBUS_ACCOUNT_ID = __ENV.BROKERAGE_OMNIBUS_ACCOUNT_ID || "ffffffff-ffff-ffff-ffff-ffffffffffff";

function deterministicUuid(prefix, vu, iter) {
    const seed = `${prefix}-${vu}-${iter}`;
    let hash = 0;
    for (let i = 0; i < seed.length; i++) {
        hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
    }
    const h = hash.toString(16).padStart(8, "0");
    const p1 = `${h}${h}`.slice(0, 8);
    const p2 = `${h}${h}`.slice(2, 6);
    const p3 = `4${`${h}${h}`.slice(5, 8)}`;
    const p4 = `a${`${h}${h}`.slice(1, 4)}`;
    const p5 = `${h}${h}${h}`.slice(0, 12);
    return `${p1}-${p2}-${p3}-${p4}-${p5}`;
}

export function readBuyingPower() {
    const res = http.get(`${BASE_URL}/api/v1/orders/buying-power/${CUSTOMER_ID}?currency=${CURRENCY}`);
    check(res, {"read status is 200/404": (r) => r.status === 200 || r.status === 404});
    sleep(0.02);
}

export function postOrderEvent() {
    const referenceId = deterministicUuid("order", __VU, __ITER);
    const payload = JSON.stringify({
        referenceId,
        eventType: "ORDER_CREATED",
        customerId: CUSTOMER_ID,
        settledCashAccountId: SETTLED_CASH_ACCOUNT_ID,
        reservedCashAccountId: RESERVED_CASH_ACCOUNT_ID,
        unsettledCashBuysAccountId: UNSETTLED_CASH_BUYS_ACCOUNT_ID,
        currency: CURRENCY,
        heldAmount: "25.00"
    });

    const res = http.post(`${BASE_URL}/api/v1/orders/events`, payload, {
        headers: {"Content-Type": "application/json", "X-Correlation-Id": `mix-order-${__VU}-${__ITER}`}
    });
    check(res, {"order status is 2xx/4xx": (r) => r.status >= 200 && r.status < 500});
    sleep(0.02);
}

export function postCashEvent() {
    const eventId = deterministicUuid("cash", __VU, __ITER);
    const payload = JSON.stringify({
        eventType: "VA_CREDITED",
        eventId,
        customerId: CUSTOMER_ID,
        settledCashAccountId: SETTLED_CASH_ACCOUNT_ID,
        settlementPendingAccountId: SETTLEMENT_PENDING_ACCOUNT_ID,
        brokerageOmnibusAccountId: BROKERAGE_OMNIBUS_ACCOUNT_ID,
        amount: "15.00",
        currency: CURRENCY
    });
    const res = http.post(`${BASE_URL}/api/v1/cash/events`, payload, {
        headers: {"Content-Type": "application/json", "X-Correlation-Id": `mix-cash-${__VU}-${__ITER}`}
    });
    check(res, {"cash status is 2xx/4xx": (r) => r.status >= 200 && r.status < 500});
    sleep(0.02);
}
