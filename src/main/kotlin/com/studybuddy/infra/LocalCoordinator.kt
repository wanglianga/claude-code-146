package com.studybuddy.infra

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 单机降级实现：进程内锁与 TTL KV。
 * 仅用于：本地无 Redis 的演示/测试，或 Redis 故障时的降级；
 * 生产多实例部署请保证 Redis 可达（compose 默认包含 redis）。
 */
class LocalCoordinator : Coordinator {

    private class Entry(val value: String, val expireAt: Long) {
        fun expired(now: Long): Boolean = expireAt in 1 until now
    }

    private val map = ConcurrentHashMap<String, Entry>()

    private fun getLive(key: String): Entry? {
        val e = map[key] ?: return null
        if (e.expired(System.currentTimeMillis())) {
            map.remove(key, e)
            return null
        }
        return e
    }

    override fun tryLock(key: String, ttlMillis: Long): String? {
        val now = System.currentTimeMillis()
        getLive(key) ?: run {
            val token = UUID.randomUUID().toString()
            val e = Entry(token, now + ttlMillis)
            val old = map.putIfAbsent(key, e)
            if (old == null || old.expired(now)) {
                if (old != null) map.replace(key, old, e)
                return token
            }
        }
        return null
    }

    override fun releaseLock(key: String, token: String): Boolean {
        val e = getLive(key) ?: return false
        return if (e.value == token) map.remove(key, e) else false
    }

    override fun setKV(key: String, value: String, ttlMillis: Long) {
        map[key] = Entry(value, if (ttlMillis > 0) System.currentTimeMillis() + ttlMillis else 0L)
    }

    override fun getKV(key: String): String? = getLive(key)?.value

    override fun setIfAbsentKV(key: String, value: String, ttlMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        val existing = getLive(key)
        if (existing != null) return false
        val e = Entry(value, if (ttlMillis > 0) now + ttlMillis else 0L)
        val old = map.putIfAbsent(key, e)
        return old == null || old.expired(now)
    }

    override fun deleteKV(key: String): Boolean = map.remove(key) != null

    override fun alive(): Boolean = true
}
