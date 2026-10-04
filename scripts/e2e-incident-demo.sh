#!/usr/bin/env bash
set -e
BASE=http://localhost:8080
T0=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)-datetime.timedelta(minutes=5)).isoformat().replace('+00:00','Z'))")
T1=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=55)).isoformat().replace('+00:00','Z'))")
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d '{"time":null}' >/dev/null

echo "== 新用户下单 A03 并进场"
U=$(curl -s -X POST "$BASE/api/users" -H 'Content-Type: application/json' -d '{"phone":"13750000001","name":"停电测试","realNameVerified":true}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
SEAT=$(curl -s "$BASE/api/stores/1/seats" | python3 -c 'import sys,json; print([s["id"] for s in json.load(sys.stdin) if s["code"]=="A03"][0])')
BK=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$U,\"storeId\":1,\"seatId\":$SEAT,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BK/pay" >/dev/null
curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$U,\"storeId\":1}" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  进场:", d["result"], d["detail"][:50])'

echo "== 门店停电：强制清场、退剩余费用+押金、释放座位"
curl -s -X POST "$BASE/api/stores/1/blackout" -H 'Content-Type: application/json' -d '{"blackout":true,"reason":"片区检修停电"}'
echo
curl -s "$BASE/api/bookings/$BK" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  状态:",d["status"],"已付:",d["paidAmount"],"已退:",d["refundedAmount"],"设备事件:",d["relatedIncidentNo"])'
curl -s "$BASE/api/stores/1/seats" | python3 -c 'import sys,json; print("  A03:", [(s["status"],s["sensorOccupied"]) for s in json.load(sys.stdin) if s["code"]=="A03"])'

echo "== 停电期间进入被拒"
curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$U,\"storeId\":1}" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  ", d["result"], "-", d["detail"])'
curl -s -X POST "$BASE/api/stores/1/blackout" -H 'Content-Type: application/json' -d '{"blackout":false,"reason":"恢复供电"}' >/dev/null

echo "== 空调故障投诉 → 自动归因 DEVICE_FAULT"
U2=$(curl -s -X POST "$BASE/api/users" -H 'Content-Type: application/json' -d '{"phone":"13750000002","name":"空调投诉","realNameVerified":true}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
BK2=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$U2,\"storeId\":1,\"seatId\":$SEAT,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BK2/pay" >/dev/null
INC=$(curl -s -X POST "$BASE/api/incidents" -H 'Content-Type: application/json' -d '{"storeId":1,"type":"AC_FAILURE","description":"A区空调不制冷","seatId":'$SEAT'}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["incidentNo"])')
echo "  设备事件=$INC"
CP=$(curl -s -X POST "$BASE/api/complaints" -H 'Content-Type: application/json' -d "{\"bookingNo\":\"$BK2\",\"category\":\"EQUIPMENT_ENV\",\"description\":\"空调不制冷\",\"incidentNo\":\"$INC\"}")
echo "$CP" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  投诉:",d["complaintNo"],"工单:",d["relatedTicketNo"])'

echo "== 运营：按门店看争议来源 + 指标"
curl -s "$BASE/api/ops/stores/1/disputes" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  争议分布:",d["byCause"]); print("  设备故障工单:",d["deviceFaultTickets"])'
curl -s "$BASE/api/ops/stores/1/metrics?from=$T0&to=$T1" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  上座率:",d["occupancyRate"],"延时率:",d["extensionRate"],"投诉率:",d["complaintRate"],"设备事件:",d["deviceIncidents"])'
echo E2E3_OK
