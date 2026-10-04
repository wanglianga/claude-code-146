import json, urllib.request, urllib.error, time

BASE = "http://localhost:8080"

def call(method, path, body=None, expect_fail=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE+path, data=data, method=method,
                                 headers={"Content-Type":"application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

results = []
def check(name, cond, extra=""):
    results.append((name, cond, extra))
    print(("PASS" if cond else "FAIL"), name, extra)

now = int(time.time()*1000)
MIN = 60000

# ============ 场景一：晚到→顺延→占下一位→投诉/换座 ============
print("\n== 场景一：无人值守连续事件（晚到/顺延/占座/投诉/换座） ==")
# 用户1（小明）预约 A-02（seatId=2）：开始时间已过 5 分钟，时长 60 分钟
s, r1 = call("POST","/api/reservations",{"userId":1,"storeId":1,"seatId":2,
    "reservationType":"SINGLE_SEAT","startTime":now-5*MIN,"endTime":now+55*MIN,
    "silentRuleAccepted":True})
check("小明下单", s==201 and r1["status"]=="PENDING_PAYMENT", f"id={r1.get('id')}")
call("POST",f"/api/reservations/{r1['id']}/pay",{})

# 用户2（小红）预约同一座位 60 分钟后
s, r2 = call("POST","/api/reservations",{"userId":2,"storeId":1,"seatId":2,
    "reservationType":"SINGLE_SEAT","startTime":now+60*MIN,"endTime":now+120*MIN,
    "silentRuleAccepted":True})
check("小红下单(下一位)", s==201, f"id={r2.get('id')}")
call("POST",f"/api/reservations/{r2['id']}/pay",{})

# 小明刷卡进场（迟到 5 分钟，在 15 分钟宽限内）
s, g = call("POST","/api/gate/attempt",{"reservationId":r1["id"],"direction":"IN","deviceSn":"GATE-A"})
check("小明迟到5分钟放行", g["result"]=="ALLOW" and g["reason"]=="OK", g["cctvEventId"])

# 小明发现要晚点走，申请顺延 30 分钟？——他已进场不能顺延；
# 真实链：他尚未进场时申请顺延。这里改为：直接申请延时（在场），会侵占小红 → 应拒绝
s, ext = call("POST",f"/api/reservations/{r1['id']}/extend",{"minutes":30})
check("小明延时30分钟被拒(侵占小红)", s==422 and ext["code"]=="RULE_REJECTED", ext.get("message",""))

# 小明2号链（A-01=seat1）：15 分钟短单，开始已过 10 分钟；小红下一位 +10 分钟到（顺延前不重叠）
s, r3 = call("POST","/api/reservations",{"userId":1,"storeId":1,"seatId":1,
    "reservationType":"SINGLE_SEAT","startTime":now-10*MIN,"endTime":now+5*MIN,
    "silentRuleAccepted":True})
call("POST",f"/api/reservations/{r3['id']}/pay",{})
s, r4 = call("POST","/api/reservations",{"userId":2,"storeId":1,"seatId":1,
    "reservationType":"SINGLE_SEAT","startTime":now+10*MIN,"endTime":now+70*MIN,
    "silentRuleAccepted":True})
call("POST",f"/api/reservations/{r4['id']}/pay",{})
s, p = call("POST",f"/api/reservations/{r3['id']}/postpone",{"delayMinutes":20})
check("小明迟到顺延20分钟(原单结束+5 -> +25，侵占+10到场的小红)", s==200 and p["postponeCount"]==1)
s, r4b = call("GET",f"/api/reservations/{r4['id']}")
check("小红单被打侵占标记", r4b["encroachedByReservationId"]==r3["id"], str(r4b["encroachedByReservationId"]))

# 小红在入场时间窗内到场（开始前10分钟），因侵占标记被拦
s, g2 = call("POST","/api/gate/attempt",{"reservationId":r4["id"],"direction":"IN","deviceSn":"GATE-A"})
check("小红进场被拒(顺延侵占)", g2["result"]=="DENY" and g2["reason"]=="SEAT_OCCUPIED", g2["reason"])

# 小红投诉
s, c = call("POST","/api/stores/1/complaints",{"reservationId":r4["id"],"userId":2,
    "reason":"SEAT_OCCUPIED","detail":"到场发现座位被上一位顺延用户占用",
    "cctvEventId":g2["cctvEventId"]})
check("小红投诉座位被占", s==201 and c["reason"]=="SEAT_OCCUPIED", f"complaintId={c['id']}")

# 客服开工单（根因：用户迟到）
s, t = call("POST","/api/tickets",{"storeId":1,"reservationId":r4["id"],"userId":2,
    "category":"SEAT_CONFLICT","rootCause":"USER_LATE","summary":"顺延导致下一位被占",
    "complaintId":c["id"]})
check("开工单(USER_LATE)", s==201, f"ticketId={t['id']}")

# 给小红换座到包间 R-01（seatId=4；A-03 是包月固定座）
s, ra = call("POST",f"/api/reservations/{r4['id']}/reassign",
    {"targetSeatId":4,"reason":"上一位顺延占用，安排包间 R-01"})
check("小红换座 R-01", s==200 and ra["seatId"]==4 and ra["movedFromSeatId"]==1)

# 小红在新座位放行
s, g3 = call("POST","/api/gate/attempt",{"reservationId":r4["id"],"direction":"IN","deviceSn":"GATE-A"})
check("小红换座后放行", g3["result"]=="ALLOW", g3["reason"])

# 证据包
s, ev = call("GET",f"/api/tickets/{t['id']}/evidence")
check("证据包含门禁日志/投诉/时间轴",
      len(ev["gateLogs"])>=1 and len(ev["complaints"])>=1 and len(ev["timeline"])>=3,
      f"gates={len(ev['gateLogs'])} complaints={len(ev['complaints'])} tl={len(ev['timeline'])}")

# 客服完结工单（补偿优惠券折现 20 元，不退座因为已换座继续使用）
s, tr = call("POST",f"/api/tickets/{t['id']}/resolve",
    {"staffId":"S01","compensation":20.00,"note":"安排换座并补偿20元","releaseSeat":False})
check("工单完结补偿20", s==200 and tr["resolvedCompensation"]==20.00)

# ============ 场景二：提前离场退费且座位释放 ============
print("\n== 场景二：提前离场退费 ==")
s, g4 = call("POST","/api/gate/attempt",{"reservationId":r2["id"],"direction":"IN","deviceSn":"GATE-A"})
# r2 是小红 A-02 的单（60 分钟后才开始，现在提前太多会被拒）。改用 r1（小明在场，A-02）
s, rr = call("GET",f"/api/reservations/{r1['id']}")
check("小明仍在场 A-02", rr["status"]=="IN_PROGRESS")
s, refund = call("POST",f"/api/reservations/{r1['id']}/early-leave-refund",{"reason":"身体不适提前走"})
check("提前离场结算成功", s==200 and refund["seatReleased"]==True, str(refund.get("status")))
s, seatA02 = call("GET","/api/stores/1/seats")
a02 = [x for x in seatA02 if x["seatCode"]=="A-02"][0]
check("退费后座位已解锁", a02["status"]=="FREE" and a02.get("occupiedByReservationId") is None)
check("有退费金额", refund["refundedAmount"] is not None and refund["refundedAmount"]>0, str(refund["refundedAmount"]))

# ============ 场景三：门店停电（门店责任批量处置） ============
print("\n== 场景三：门店停电 ==")
s, de = call("POST","/api/stores/1/device-events",
    {"deviceType":"POWER","responsible":"STORE","note":"楼层配电检修跳闸","cctvEventId":"CCTV-POWER-001"})
check("停电事件登记", s==201 and len(de["affectedReservationIds"])>=1, str(de["affectedReservationIds"]))
# 停电期间新进场被拒
s, r5 = call("POST","/api/reservations",{"userId":2,"storeId":1,"seatId":5,
    "reservationType":"NIGHT_SEAT","startTime":now,"endTime":now+120*MIN,
    "needLocker":True,"silentRuleAccepted":True})
call("POST",f"/api/reservations/{r5['id']}/pay",{})
s, g5 = call("POST","/api/gate/attempt",{"reservationId":r5["id"],"direction":"IN"})
check("停电期间门禁拒绝进场", g5["result"]=="DENY" and g5["reason"]=="STORE_OUTAGE")
# 在场用户离场不受限（r4 小红在场）
s, g6 = call("POST","/api/gate/attempt",{"reservationId":r4["id"],"direction":"OUT"})
check("停电期间允许离场", g6["result"]=="ALLOW", g6["reason"])
# 恢复
s, der = call("POST","/api/stores/1/device-events/{}/resolve?note=power%20restored".format(de['id']))
check("供电恢复", s==200 and der["eventType"]=="RESOLVED")

# ============ 场景四：空调故障 → 投诉自动关联设备事件 → 门店责任工单 ============
print("\n== 场景四：空调故障 ==")
s, ac = call("POST","/api/stores/1/device-events",
    {"deviceType":"AC","seatId":2,"responsible":"VENDOR","note":"包间区空调不制冷"})
check("空调故障上报", s==201)
s, ca = call("POST","/api/stores/1/complaints",{"reservationId":None,"userId":1,"seatId":2,
    "reason":"AC_FAULT","detail":"A-02 太热"})
check("空调投诉自动关联设备事件", ca["deviceEventId"]==ac["id"])
s, ta = call("POST","/api/tickets",{"storeId":1,"reservationId":None,"userId":1,
    "category":"EQUIPMENT_FAULT","rootCause":"DEVICE_FAULT","summary":"空调故障投诉"})
check("设备责任工单", ta["rootCause"]=="DEVICE_FAULT")

# ============ 场景五：邻座噪音投诉 + 申诉 ============
print("\n== 场景五：邻座噪音 ==")
s, cn = call("POST","/api/stores/1/complaints",{"userId":2,"againstUserId":1,"seatId":1,
    "reason":"NOISE_NEIGHBOR","detail":"邻座大声讲话","cctvEventId":"CCTV-N-77"})
check("噪音投诉", cn["reason"]=="NOISE_NEIGHBOR")
call("POST","/api/appeals",{"userId":1,"content":"当时是在接紧急电话，已压低声音","ticketId":ta["id"]})

# ============ 场景六：包月固定座权益冲突 ============
print("\n== 场景六：包月权益冲突 ==")
# seat3 是月月的固定座；今天普通预约应被拒（用今天的时间）
today0 = now
s, rp = call("POST","/api/reservations",{"userId":1,"storeId":1,"seatId":3,
    "reservationType":"SINGLE_SEAT","startTime":today0,"endTime":today0+120*MIN,
    "silentRuleAccepted":True})
check("普通预约占用包月固定座被拒", s==422 and rp["code"]=="RULE_REJECTED", rp.get("message",""))
# 月月自己用包月下单 seat3，金额 0
s, mp = call("GET","/api/passes/by-user/3")
passId = mp[0]["id"]
s, rm = call("POST","/api/reservations",{"userId":3,"storeId":1,"seatId":3,
    "reservationType":"MONTHLY","startTime":today0,"endTime":today0+120*MIN,
    "passId":passId,"silentRuleAccepted":True})
check("包月用户权益下单0元直接BOOKED", s==201 and rm["price"]==0 and rm["status"]=="BOOKED")

# ============ 场景七：运营报表 + 对账 ============
print("\n== 场景七：报表与对账 ==")
s, rep = call("GET",f"/api/ops/stores/1/report?from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z")
check("门店报表", s==200 and rep["totalSeats"]==5,
      f"上座率={rep['occupancyRate']} 延时率={rep['overtimeRate']} 投诉率={rep['complaintRate']} 设备故障={rep['deviceFaultCount']}")
check("报表区分争议来源", rep["disputesFromDeviceFault"]>=1 and rep["disputesFromUserLateOrAbuse"]>=1,
      str(rep["disputesByRootCause"]))
s, rec = call("POST","/api/ops/reconcile")
check("对账执行", s==200, f"repairs={len(rec['repairs'])}")

# ============ 场景八：门禁打不开（门锁故障）============
print("\n== 场景八：门锁故障 ==")
call("POST","/api/stores/1/device-events",{"deviceType":"DOOR_LOCK","responsible":"VENDOR","note":"B区门锁离线"})
s, g7 = call("POST","/api/gate/attempt",{"reservationId":r5["id"],"direction":"IN"})
check("门锁故障时拒绝进场并留痕", g7["result"]=="DENY" and g7["reason"]=="GATE_DEVICE_FAULT")

fails = [x for x in results if not x[1]]
print(f"\n==== 总计 {len(results)} 项，失败 {len(fails)} 项 ====")
if fails:
    for f in fails: print("FAILED:", f[0], f[2])
    raise SystemExit(1)
