package com.studybuddy.bootstrap

import com.studybuddy.domain.*
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 演示种子数据：
 * - 门店 1：24h 无人值守自习室（单座 A-01/A-02、包间 R-01、夜间座 N-01，部分带储物柜）
 * - 门店 2：有人值守旗舰店
 * - 用户：小明（已实名）、小红（已实名）、月月（包月固定座用户）
 */
@Component
@ConditionalOnProperty(name = ["studyroom.seed.enabled"], havingValue = "true", matchIfMissing = true)
class SeedDataRunner(
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val userRepo: AppUserRepository,
    private val passRepo: MonthlyPassRepository
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments?) {
        if (storeRepo.count() > 0) return

        val unmanned = storeRepo.save(Store(name = "24h无人值守自习室(科技园店)", address = "科技园 B 座 12 层", unattended = true))
        val manned = storeRepo.save(Store(name = "晨光旗舰店(市民中心店)", address = "市民中心负一层", unattended = false))

        seatRepo.save(Seat(unmanned, "A-01", SeatType.SINGLE_SEAT, "静音区",
            hourlyPrice = BigDecimal("12.00"), overtimePerMinute = BigDecimal("0.50"), hasLocker = true))
        seatRepo.save(Seat(unmanned, "A-02", SeatType.SINGLE_SEAT, "静音区",
            hourlyPrice = BigDecimal("12.00"), overtimePerMinute = BigDecimal("0.50"), hasLocker = false))
        seatRepo.save(Seat(unmanned, "A-03", SeatType.SINGLE_SEAT, "轻音区",
            hourlyPrice = BigDecimal("10.00"), overtimePerMinute = BigDecimal("0.40"), hasLocker = false))
        seatRepo.save(Seat(unmanned, "R-01", SeatType.PRIVATE_ROOM, "包间区",
            hourlyPrice = BigDecimal("30.00"), overtimePerMinute = BigDecimal("1.00"), hasLocker = true))
        seatRepo.save(Seat(unmanned, "N-01", SeatType.NIGHT_SEAT, "夜间区",
            hourlyPrice = BigDecimal("8.00"), overtimePerMinute = BigDecimal("0.30"), hasLocker = true))
        seatRepo.save(Seat(manned, "B-01", SeatType.SINGLE_SEAT, "静音区",
            hourlyPrice = BigDecimal("15.00"), overtimePerMinute = BigDecimal("0.60"), hasLocker = true))

        userRepo.save(AppUser(name = "小明", phone = "13800000001",
            idVerified = true, idCardMasked = "3101**********0011"))
        userRepo.save(AppUser(name = "小红", phone = "13800000002",
            idVerified = true, idCardMasked = "3101**********0022"))
        userRepo.save(AppUser(name = "月月", phone = "13800000003",
            idVerified = true, idCardMasked = "3101**********0033"))
        userRepo.save(AppUser(name = "未实名小刚", phone = "13800000004",
            idVerified = false, idCardMasked = null))

        val yueyue = userRepo.findByPhone("13800000003")!!
        val a03 = seatRepo.findByStoreId(unmanned.id!!).first { it.seatCode == "A-03" }
        passRepo.save(
            MonthlyPass(
                passNo = "MP-SEED-A03",
                user = yueyue,
                storeId = unmanned.id!!,
                fixedSeat = a03,
                mode = PassMode.FIXED_SEAT,
                validFrom = LocalDate.now().withDayOfMonth(1),
                validTo = LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1),
                status = PassStatus.ACTIVE,
                price = BigDecimal("699.00")
            )
        )

        log.info("种子数据完成：2 门店 / 6 座位 / 4 用户 / 1 包月权益")
    }
}
