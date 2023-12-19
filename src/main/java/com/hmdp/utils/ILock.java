package com.hmdp.utils;

/**
 * 分布式锁接口
 */
public interface ILock {

    /**
     * 非阻塞式：尝试获取锁
     *
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
