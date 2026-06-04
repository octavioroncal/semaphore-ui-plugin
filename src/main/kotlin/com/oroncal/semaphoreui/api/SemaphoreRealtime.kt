package com.oroncal.semaphoreui.api

interface SemaphoreRealtimeListener {
    fun onOpen()

    fun onMessage(message: SemaphoreRealtimeMessage)

    fun onFailure(throwable: Throwable)

    fun onClosed()
}

fun interface SemaphoreRealtimeConnection {
    fun close()
}
