package com.studybuddy.infra

/** 分布式协调原语：跨实例互斥锁 + 带 TTL 的 KV（门禁凭证/座位镜像/幂等） */
interface Coordinator {
    /** SET NX PX：成功返回持有者 token，失败返回 null */
    fun tryLock(key: String, ttlMillis: Long): String?

    /** 仅当 token 匹配时释放（Lua 原子） */
    fun releaseLock(key: String, token: String): Boolean

    fun setKV(key: String, value: String, ttlMillis: Long = -1L)
    fun getKV(key: String): String?
    fun setIfAbsentKV(key: String, value: String, ttlMillis: Long = -1L): Boolean
    fun deleteKV(key: String): Boolean
    fun alive(): Boolean
}

class LockAcquireException(key: String) : RuntimeException("获取分布式锁失败: $key")
