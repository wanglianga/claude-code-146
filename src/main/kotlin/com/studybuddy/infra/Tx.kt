package com.studybuddy.infra

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 供“先抢分布式锁、再开本地事务”的服务使用 */
@Component
class Tx(txManager: PlatformTransactionManager) {
    private val template = TransactionTemplate(txManager)

    fun <T> run(block: () -> T): T = template.execute { block() }!!

    fun runNoResult(block: () -> Unit) {
        template.executeWithoutResult { block() }
    }
}
