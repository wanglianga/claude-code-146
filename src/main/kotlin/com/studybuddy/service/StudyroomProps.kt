package com.studybuddy.service

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "studyroom")
data class StudyroomProps(
    val gate: GateProps = GateProps(),
    val pricing: PricingProps = PricingProps()
)

data class GateProps(
    val earlyEntryMinutes: Int = 15,
    val lateGraceMinutes: Int = 15,
    val noShowMinutes: Int = 30
)

data class PricingProps(
    val defaultOvertimePerMinute: Double = 0.5,
    val refundUsedCapRatio: Double = 0.5
)
