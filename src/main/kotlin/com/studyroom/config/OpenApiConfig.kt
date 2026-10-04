package com.studyroom.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("城市共享自习室预约 / 门禁延时与退费服务 API")
            .version("1.0.0")
            .description(
                """
                覆盖：单座/包间/夜间座/包月预约、支付与押金、进出门禁、迟到顺延与连续碰撞、
                临时延时、座位被占、设备与停电、邻座噪音投诉、客服争议工单、退费与门店责任，
                以及上座率/延时率/投诉率等运营指标。

                一致性保证：门禁放行与订单状态、退款完成与座位释放均在同一业务事务内完成；
                Redis 负责座位短租锁与实时占用；统一时间轴 /timeline 可供争议回放。
                """.trimIndent()
            )
            .contact(Contact().name("studyroom-platform"))
    )
}
