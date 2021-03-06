/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.event

import kotlinx.coroutines.*
import net.mamoe.mirai.message.MessagePacket
import net.mamoe.mirai.message.isContextIdenticalWith
import net.mamoe.mirai.message.nextMessage
import net.mamoe.mirai.utils.MiraiExperimentalAPI
import net.mamoe.mirai.utils.SinceMirai
import kotlin.experimental.ExperimentalTypeInference
import kotlin.jvm.JvmName
import kotlin.jvm.JvmSynthetic


/**
 * 挂起当前协程, 等待任意一个事件监听器返回 `false` 后返回.
 *
 * 创建的所有事件监听器都会判断发送人信息 ([isContextIdenticalWith]), 监听之后的所有消息.
 *
 * ```kotlin
 * reply("开启复读模式")
 *
 * whileSelectMessages {
 *     "stop" `->` {
 *         reply("已关闭复读")
 *         false // 停止循环
 *     }
 *     always {
 *         reply(message)
 *         true // 继续循环
 *     }
 * } // 等待直到 `false`
 *
 * reply("复读模式结束")
 * ```
 *
 * @param timeoutMillis 超时. 单位为毫秒. `-1` 为不限制
 *
 * @see subscribe
 * @see subscribeMessages
 * @see nextMessage 挂起协程并等待下一条消息
 */
@SinceMirai("0.29.0")
@Suppress("unused")
@MiraiExperimentalAPI
suspend inline fun <reified T : MessagePacket<*, *>> T.whileSelectMessages(
    timeoutMillis: Long = -1,
    crossinline selectBuilder: @MessageDsl MessageSelectBuilder<T, Boolean>.() -> Unit
) = withTimeoutOrCoroutineScope(timeoutMillis) {
    var deferred: CompletableDeferred<Boolean>? = CompletableDeferred()

    // ensure sequential invoking
    val listeners: MutableList<Pair<T.(String) -> Boolean, MessageListener<T, Any?>>> = mutableListOf()

    MessageSelectBuilder<T, Boolean>(SELECT_MESSAGE_STUB) { filter: T.(String) -> Boolean, listener: MessageListener<T, Any?> ->
        listeners += filter to listener
    }.apply(selectBuilder)

    // ensure atomic completing
    subscribeAlways<T>(concurrency = Listener.ConcurrencyKind.LOCKED) { event ->
        if (!this.isContextIdenticalWith(this@whileSelectMessages))
            return@subscribeAlways

        listeners.forEach { (filter, listener) ->
            if (deferred?.isCompleted != false || !isActive)
                return@subscribeAlways

            val toString = event.message.toString()
            if (filter.invoke(event, toString)) {
                listener.invoke(event, toString).let { value ->
                    if (value !== SELECT_MESSAGE_STUB) {
                        deferred?.complete(value as Boolean)
                        return@subscribeAlways // accept the first value only
                    }
                }
            }
        }
    }

    while (deferred?.await() == true) {
        deferred = CompletableDeferred()
    }
    deferred = null
    coroutineContext[Job]!!.cancelChildren()
}

/**
 * [selectMessages] 的 [Unit] 返回值捷径 (由于 Kotlin 无法推断 [Unit] 类型)
 */
@OptIn(ExperimentalTypeInference::class)
@MiraiExperimentalAPI
@SinceMirai("0.29.0")
@JvmName("selectMessages1")
suspend inline fun <reified T : MessagePacket<*, *>> T.selectMessagesUnit(
    timeoutMillis: Long = -1,
    crossinline selectBuilder: @MessageDsl MessageSelectBuilder<T, Unit>.() -> Unit
) = selectMessages(timeoutMillis, selectBuilder)


/**
 * 挂起当前协程, 等待任意一个事件监听器触发后返回其返回值.
 *
 * 创建的所有事件监听器都会判断发送人信息 ([isContextIdenticalWith]), 监听之后的所有消息.
 *
 * ```kotlin
 * val value: String = selectMessages {
 *   "hello" `->` { "111" }
 *   "hi" `->` { "222" }
 *   startsWith("/") { it }
 * }
 * ```
 *
 * @param timeoutMillis 超时. 单位为毫秒. `-1` 为不限制
 *
 * @see nextMessage 挂起协程并等待下一条消息
 */
@MiraiExperimentalAPI
@SinceMirai("0.29.0")
@Suppress("unused") // false positive
@OptIn(ExperimentalTypeInference::class)
@BuilderInference
suspend inline fun <reified T : MessagePacket<*, *>, R> T.selectMessages(
    timeoutMillis: Long = -1,
    @BuilderInference
    crossinline selectBuilder: @MessageDsl MessageSelectBuilder<T, R>.() -> Unit
): R = withTimeoutOrCoroutineScope(timeoutMillis) {
    val deferred = CompletableDeferred<R>()

    // ensure sequential invoking
    val listeners: MutableList<Pair<T.(String) -> Boolean, MessageListener<T, Any?>>> = mutableListOf()

    MessageSelectBuilder<T, R>(SELECT_MESSAGE_STUB) { filter: T.(String) -> Boolean, listener: MessageListener<T, Any?> ->
        listeners += filter to listener
    }.apply(selectBuilder)

    subscribeAlways<T> { event ->
        if (!this.isContextIdenticalWith(this@selectMessages))
            return@subscribeAlways

        listeners.forEach { (filter, listener) ->
            if (deferred.isCompleted || !isActive)
                return@subscribeAlways

            val toString = event.message.toString()
            if (filter.invoke(event, toString)) {
                val value = listener.invoke(event, toString)
                if (value !== SELECT_MESSAGE_STUB) {
                    @Suppress("UNCHECKED_CAST")
                    deferred.complete(value as R)
                    return@subscribeAlways
                }
            }
        }
    }

    deferred.await().also { coroutineContext[Job]!!.cancelChildren() }
}

@SinceMirai("0.29.0")
class MessageSelectBuilder<M : MessagePacket<*, *>, R> @PublishedApi internal constructor(
    stub: Any?,
    subscriber: (M.(String) -> Boolean, MessageListener<M, Any?>) -> Unit
) : MessageSubscribersBuilder<M, Unit, R, Any?>(stub, subscriber)

@JvmSynthetic
@PublishedApi
internal suspend inline fun <R> withTimeoutOrCoroutineScope(
    timeoutMillis: Long,
    noinline block: suspend CoroutineScope.() -> R
): R {
    require(timeoutMillis == -1L || timeoutMillis > 0) { "timeoutMillis must be -1 or > 0 " }

    return if (timeoutMillis == -1L) {
        coroutineScope(block)
    } else {
        withTimeout(timeoutMillis, block)
    }
}

@PublishedApi
internal val SELECT_MESSAGE_STUB = Any()