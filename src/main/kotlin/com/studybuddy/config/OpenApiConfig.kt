package com.studybuddy.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("城市共享自习室预约/门禁/延时/退费服务 API")
            .version("1.0.0")
            .description(
                """
                覆盖：预约（单座/包间/夜间座/包月）、支付与押金、实名门禁进出时间窗、
                迟到顺延与下一位侵占/换座、临时延时计费、设备故障与门店停电、
                邻座噪音投诉、客服工单证据包、提前离场退费、门店运营报表与一致性对账。

                Swagger UI: /swagger-ui.html ；OpenAPI JSON: /v3/api-docs
                """.trimIndent()
            )
    )
}
