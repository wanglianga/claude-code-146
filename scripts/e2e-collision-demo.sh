#!/usr/bin/env bash
set -e
BASE=http://localhost:8080

read T0 T_END T_NEXT < <(python3 - <<'PY'
import datetime
base = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0) + datetime.timedelta(hours=1)
t1 = base + datetime.timedelta(hours=1)
t2 = base + datetime.timedelta(hours=2)
f = lambda x: x.isoformat().replace('+00:00','Z')
print(f(base), f(t1), f(t2))
PY
)

echo "== 1. 种子数据"
STORE=$(curl -s "$BASE/api/stores" | python3 -c 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
USERS=$(curl -s "$BASE/api/users")
UID_A=$(echo "$USERS" | python3 -c 'import sys,json; print([u["id"] for u in json.load(sys.stdin) if u["phone"]=="13800000001"][0])')
UID_B=$(echo "$USERS" | python3 -c 'import sys,json; print([u["id"] for u in json.load(sys.stdin) if u["phone"]=="13800000002"][0])')
SEAT=$(curl -s "$BASE/api/stores/$STORE/seats" | python3 -c 'import sys,json; print([s["id"] for s in json.load(sys.stdin) if s["code"]=="A01"][0])')
echo "   门店=$STORE A用户=$UID_A B用户=$UID_B 座位A01=$SEAT"
echo "   T0=$T0  T_END=$T_END  T_NEXT=$T_NEXT"

echo "== 2. 虚拟时钟置 T0；A/B 连续两小时预约 A01 并支付"
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$T0\"}" >/dev/null
BK_A=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' \
  -d "{\"userId\":$UID_A,\"storeId\":$STORE,\"seatId\":$SEAT,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T_END\",\"silenceRuleAcknowledged\":true}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
BK_B=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' \
  -d "{\"userId\":$UID_B,\"storeId\":$STORE,\"seatId\":$SEAT,\"type\":\"SINGLE\",\"startTime\":\"$T_END\",\"endTime\":\"$T_NEXT\",\"silenceRuleAcknowledged\":true}" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BK_A/pay" >/dev/null
curl -s -X POST "$BASE/api/bookings/$BK_B/pay" >/dev/null
echo "   A单=$BK_A  B单=$BK_B（均已支付 CONFIRMED）"

echo "== 3. A 迟到 10 分钟申请顺延 40 分钟"
T_LATE=$(python3 -c "import datetime;print((datetime.datetime.fromisoformat('$T0'[:-1]+'+00:00')+datetime.timedelta(minutes=10)).isoformat().replace('+00:00','Z'))")
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$T_LATE\"}" >/dev/null
curl -s -X POST "$BASE/api/bookings/$BK_A/postpone" -H 'Content-Type: application/json' -d '{"minutes":40}' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("   顺延批准，extendedEnd =",d["extendedEndTime"],"加收",d["extraChargedAmount"],"元")'
curl -s "$BASE/api/bookings/$BK_B/timeline" | python3 -c 'import sys,json; ev=[e["eventType"] for e in json.load(sys.stdin) if "COLLISION" in e["eventType"]]; print("   B单碰撞预警:", ev)'

echo "== 4. A 进场"
curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$UID_A,\"storeId\":$STORE}" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ", d["result"], d["detail"][:70])'
curl -s "$BASE/api/bookings/$BK_A" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("   A订单状态 =",d["status"],"迟到 =",d["late"],d["lateMinutes"],"分钟")'

echo "== 5. B 到点进场：A01 被顺延的 A 占用 → 自动换座"
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$T_END\"}" >/dev/null
curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$UID_B,\"storeId\":$STORE}" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ", d["result"], "座位 =", d["seatCode"]); print("  ", d["detail"][:100])'
curl -s "$BASE/api/bookings/$BK_B" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("   B订单状态 =",d["status"],"现座位 =",d["seatCode"],"原座位id =",d["originalSeatId"])'

echo "== 6. B 投诉座位被占"
TK=$(curl -s -X POST "$BASE/api/complaints" -H 'Content-Type: application/json' \
  -d "{\"bookingNo\":\"$BK_B\",\"category\":\"SEAT_OCCUPIED\",\"description\":\"到店发现 A01 被顺延订单占用，自动换座\",\"againstBookingNo\":\"$BK_A\"}" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["relatedTicketNo"])')
echo "   自动生成客服工单 = $TK"

echo "== 7. 客服证据包"
curl -s "$BASE/api/tickets/$TK/evidence" | python3 -c '
import sys,json
d=json.load(sys.stdin)
print("   工单归因 =", d["ticket"]["cause"], "| 门禁日志", len(d["accessLogs"]), "条 | 时间轴", len(d["timeline"]), "条")
print("   监控编号:", [a["monitorEventNo"] for a in d["accessLogs"]][:2])
'

echo "== 8. 申诉 + 客服判共担责任补偿 3 元并结案"
curl -s -X POST "$BASE/api/tickets/$TK/appeal" -H 'Content-Type: application/json' -d '{"text":"换座影响体验，申请补偿"}' >/dev/null
curl -s -X POST "$BASE/api/tickets/$TK/decide" -H 'Content-Type: application/json' \
  -d '{"responsibility":"SHARED","refundAmount":3.00,"note":"顺延碰撞导致换座，补偿3元"}' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("   责任 =",d["responsibility"],"状态 =",d["status"],"处理:",d["staffNote"][:60])'
curl -s "$BASE/api/bookings/$BK_B/refunds" | python3 -c 'import sys,json; print("   B单退款记录:", [(r["type"],str(r["amount"])) for r in json.load(sys.stdin)])'

echo "== 9. A/B 离场，座位释放"
curl -s -X POST "$BASE/api/access/leave" -H 'Content-Type: application/json' -d "{\"userId\":$UID_A,\"storeId\":$STORE}" >/dev/null
curl -s -X POST "$BASE/api/access/leave" -H 'Content-Type: application/json' -d "{\"userId\":$UID_B,\"storeId\":$STORE}" >/dev/null
curl -s "$BASE/api/stores/$STORE/seats" | python3 -c '
import sys,json
d=json.load(sys.stdin)
print("  ", {s["code"]:(s["status"],s["sensorOccupied"]) for s in d if s["code"] in ("A01","A02")})'

echo "== 10. 运营端：争议来源分类"
curl -s "$BASE/api/ops/stores/$STORE/disputes" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("   争议总数 =",d["total"],"分布 =",d["byCause"])'

echo "== 11. 未实名用户门禁被拒"
UID_C=$(curl -s "$BASE/api/users" | python3 -c 'import sys,json; print([u["id"] for u in json.load(sys.stdin) if u["phone"]=="13800000003"][0])')
curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$UID_C,\"storeId\":$STORE}" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ", d["result"], "-", d["detail"])'

echo "E2E_OK"
