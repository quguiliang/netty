/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ResourceLeakHint;
import io.netty.util.concurrent.AbstractEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.OrderedEventExecutor;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.ObjectPool.Handle;
import io.netty.util.internal.ObjectPool.ObjectCreator;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.channel.ChannelHandlerMask.MASK_BIND;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_ACTIVE;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_INACTIVE;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_READ;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_READ_COMPLETE;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_REGISTERED;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_UNREGISTERED;
import static io.netty.channel.ChannelHandlerMask.MASK_CHANNEL_WRITABILITY_CHANGED;
import static io.netty.channel.ChannelHandlerMask.MASK_CLOSE;
import static io.netty.channel.ChannelHandlerMask.MASK_CONNECT;
import static io.netty.channel.ChannelHandlerMask.MASK_DEREGISTER;
import static io.netty.channel.ChannelHandlerMask.MASK_DISCONNECT;
import static io.netty.channel.ChannelHandlerMask.MASK_EXCEPTION_CAUGHT;
import static io.netty.channel.ChannelHandlerMask.MASK_FLUSH;
import static io.netty.channel.ChannelHandlerMask.MASK_ONLY_INBOUND;
import static io.netty.channel.ChannelHandlerMask.MASK_ONLY_OUTBOUND;
import static io.netty.channel.ChannelHandlerMask.MASK_READ;
import static io.netty.channel.ChannelHandlerMask.MASK_USER_EVENT_TRIGGERED;
import static io.netty.channel.ChannelHandlerMask.MASK_WRITE;
import static io.netty.channel.ChannelHandlerMask.mask;

abstract class AbstractChannelHandlerContext implements ChannelHandlerContext, ResourceLeakHint {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannelHandlerContext.class);
    volatile AbstractChannelHandlerContext next;
    volatile AbstractChannelHandlerContext prev;

    private static final AtomicIntegerFieldUpdater<AbstractChannelHandlerContext> HANDLER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractChannelHandlerContext.class, "handlerState");

    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} is about to be called.
     */
    private static final int ADD_PENDING = 1;
    /**
     * {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called.
     */
    private static final int ADD_COMPLETE = 2;
    /**
     * {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    private static final int REMOVE_COMPLETE = 3;
    /**
     * Neither {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}
     * nor {@link ChannelHandler#handlerRemoved(ChannelHandlerContext)} was called.
     */
    private static final int INIT = 0;

    private final DefaultChannelPipeline pipeline;
    private final String name;
    private final boolean ordered;
    private final int executionMask;

    // Will be set to null if no child executor should be used, otherwise it will be set to the
    // child executor.
    final EventExecutor executor;
    private ChannelFuture succeededFuture;

    // Lazily instantiated tasks used to trigger events to a handler with different executor.
    // There is no need to make this volatile as at worse it will just create a few more instances then needed.
    private Tasks invokeTasks;

    private volatile int handlerState = INIT;

    AbstractChannelHandlerContext(DefaultChannelPipeline pipeline, EventExecutor executor,
                                  String name, Class<? extends ChannelHandler> handlerClass) {
        this.name = ObjectUtil.checkNotNull(name, "name");
        this.pipeline = pipeline;
        this.executor = executor;
        this.executionMask = mask(handlerClass);
        // Its ordered if its driven by the EventLoop or the given Executor is an instanceof OrderedEventExecutor.
        ordered = executor == null || executor instanceof OrderedEventExecutor;
    }

    @Override
    public Channel channel() {
        return pipeline.channel();
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return channel().config().getAllocator();
    }

    //如果未设置子执行器，则使用 Channel 的 EventLoop 作为执行器。
    // 😈 一般情况下，我们可以忽略子执行器的逻辑，也就是说，可以直接认为是使用 Channel 的 EventLoop 作为执行器
    @Override
    public EventExecutor executor() {
        if (executor == null) {
            return channel().eventLoop();
        } else {
            return executor;
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChannelHandlerContext fireChannelRegistered() {
        invokeChannelRegistered(findContextInbound(MASK_CHANNEL_REGISTERED));
        return this;
    }

    static void invokeChannelRegistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelRegistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRegistered();
                }
            });
        }
    }

    private void invokeChannelRegistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRegistered(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelRegistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelUnregistered() {
        invokeChannelUnregistered(findContextInbound(MASK_CHANNEL_UNREGISTERED));
        return this;
    }

    static void invokeChannelUnregistered(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelUnregistered();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelUnregistered();
                }
            });
        }
    }

    private void invokeChannelUnregistered() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelUnregistered(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelUnregistered();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelActive() {
        // 获得下一个 Inbound 节点的执行器
        // 调用下一个 Inbound 节点的 Channel active 方法
        invokeChannelActive(findContextInbound(MASK_CHANNEL_ACTIVE));
        return this;
    }

    static void invokeChannelActive(final AbstractChannelHandlerContext next) {
        // 获得下一个 Inbound 节点的执行器
        EventExecutor executor = next.executor();
        // 调用下一个 Inbound 节点的 ChannelActive 方法
        if (executor.inEventLoop()) {
            next.invokeChannelActive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelActive();
                }
            });
        }
    }

    private void invokeChannelActive() {
        if (invokeHandler()) { // 判断是否符合的 ChannelHandler
            try {
                // 调用该 ChannelHandler 的 ChannelActive 方法
                ((ChannelInboundHandler) handler()).channelActive(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);   // 通知 Inbound 事件的传播，发生异常
            }
        } else {
            // 跳过，传播 Inbound 事件给下一个节点
            fireChannelActive();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelInactive() {
        invokeChannelInactive(findContextInbound(MASK_CHANNEL_INACTIVE));
        return this;
    }

    static void invokeChannelInactive(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelInactive();
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelInactive();
                }
            });
        }
    }

    private void invokeChannelInactive() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelInactive(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelInactive();
        }
    }

    @Override
    public ChannelHandlerContext fireExceptionCaught(final Throwable cause) {
        invokeExceptionCaught(findContextInbound(MASK_EXCEPTION_CAUGHT), cause);
        return this;
    }

    static void invokeExceptionCaught(final AbstractChannelHandlerContext next, final Throwable cause) {
        ObjectUtil.checkNotNull(cause, "cause");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeExceptionCaught(cause);
        } else {
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        next.invokeExceptionCaught(cause);
                    }
                });
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to submit an exceptionCaught() event.", t);
                    logger.warn("The exceptionCaught() event that was failed to submit was:", cause);
                }
            }
        }
    }

    private void invokeExceptionCaught(final Throwable cause) {
        if (invokeHandler()) {
            try {
                handler().exceptionCaught(this, cause);
            } catch (Throwable error) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                        "An exception {}" +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:",
                        ThrowableUtil.stackTraceToString(error), cause);
                } else if (logger.isWarnEnabled()) {
                    logger.warn(
                        "An exception '{}' [enable DEBUG level for full stacktrace] " +
                        "was thrown by a user handler's exceptionCaught() " +
                        "method while handling the following exception:", error, cause);
                }
            }
        } else {
            fireExceptionCaught(cause);
        }
    }

    @Override
    public ChannelHandlerContext fireUserEventTriggered(final Object event) {
        invokeUserEventTriggered(findContextInbound(MASK_USER_EVENT_TRIGGERED), event);
        return this;
    }

    static void invokeUserEventTriggered(final AbstractChannelHandlerContext next, final Object event) {
        ObjectUtil.checkNotNull(event, "event");
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeUserEventTriggered(event);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeUserEventTriggered(event);
                }
            });
        }
    }

    private void invokeUserEventTriggered(Object event) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).userEventTriggered(this, event);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireUserEventTriggered(event);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelRead(final Object msg) {
        invokeChannelRead(findContextInbound(MASK_CHANNEL_READ), msg);
        return this;
    }

    static void invokeChannelRead(final AbstractChannelHandlerContext next, Object msg) {
        final Object m = next.pipeline.touch(ObjectUtil.checkNotNull(msg, "msg"), next);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelRead(m);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    next.invokeChannelRead(m);
                }
            });
        }
    }

    private void invokeChannelRead(Object msg) {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelRead(this, msg);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelRead(msg);
        }
    }

    @Override
    public ChannelHandlerContext fireChannelReadComplete() {
        invokeChannelReadComplete(findContextInbound(MASK_CHANNEL_READ_COMPLETE));
        return this;
    }

    static void invokeChannelReadComplete(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelReadComplete();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelReadCompleteTask);
        }
    }

    private void invokeChannelReadComplete() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelReadComplete(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelReadComplete();
        }
    }

    @Override
    public ChannelHandlerContext fireChannelWritabilityChanged() {
        invokeChannelWritabilityChanged(findContextInbound(MASK_CHANNEL_WRITABILITY_CHANGED));
        return this;
    }

    static void invokeChannelWritabilityChanged(final AbstractChannelHandlerContext next) {
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeChannelWritabilityChanged();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeChannelWritableStateChangedTask);
        }
    }

    private void invokeChannelWritabilityChanged() {
        if (invokeHandler()) {
            try {
                ((ChannelInboundHandler) handler()).channelWritabilityChanged(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            fireChannelWritabilityChanged();
        }
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, newPromise());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, newPromise());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(newPromise());
    }

    @Override
    public ChannelFuture close() {
        return close(newPromise());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(newPromise());
    }

    @Override
    public ChannelFuture bind(final SocketAddress localAddress, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(localAddress, "localAddress");
        // 判断是否为合法的 Promise 对象
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        // 获得下一个 Outbound 节点
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_BIND);
        // 获得下一个 Outbound 节点的执行器
        EventExecutor executor = next.executor();
        // 调用下一个 Outbound 节点的 bind 方法
        if (executor.inEventLoop()) {
            next.invokeBind(localAddress, promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeBind(localAddress, promise);
                }
            }, promise, null, false);
        }
        return promise;
    }

    private void invokeBind(SocketAddress localAddress, ChannelPromise promise) {
        if (invokeHandler()) { // 判断是否符合的 ChannelHandler
            try {
                // 调用该 ChannelHandler 的 bind 方法
                ((ChannelOutboundHandler) handler()).bind(this, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise); // 通知 Outbound 事件的传播，发生异常
            }
        } else {
            // 跳过，传播 Outbound 事件给下一个节点
            bind(localAddress, promise);
        }
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return connect(remoteAddress, null, promise);
    }

    @Override
    public ChannelFuture connect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");

        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_CONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeConnect(remoteAddress, localAddress, promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeConnect(remoteAddress, localAddress, promise);
                }
            }, promise, null, false);
        }
        return promise;
    }

    private void invokeConnect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).connect(this, remoteAddress, localAddress, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            connect(remoteAddress, localAddress, promise);
        }
    }

    @Override
    public ChannelFuture disconnect(final ChannelPromise promise) {
        if (!channel().metadata().hasDisconnect()) {
            // Translate disconnect to close if the channel has no notion of disconnect-reconnect.
            // So far, UDP/IP is the only transport that has such behavior.
            return close(promise);
        }
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_DISCONNECT);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDisconnect(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDisconnect(promise);
                }
            }, promise, null, false);
        }
        return promise;
    }

    private void invokeDisconnect(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).disconnect(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            disconnect(promise);
        }
    }

    @Override
    public ChannelFuture close(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_CLOSE);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeClose(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeClose(promise);
                }
            }, promise, null, false);
        }

        return promise;
    }

    private void invokeClose(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).close(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            close(promise);
        }
    }

    @Override
    public ChannelFuture deregister(final ChannelPromise promise) {
        if (isNotValidPromise(promise, false)) {
            // cancelled
            return promise;
        }

        final AbstractChannelHandlerContext next = findContextOutbound(MASK_DEREGISTER);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeDeregister(promise);
        } else {
            safeExecute(executor, new Runnable() {
                @Override
                public void run() {
                    next.invokeDeregister(promise);
                }
            }, promise, null, false);
        }

        return promise;
    }

    private void invokeDeregister(ChannelPromise promise) {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).deregister(this, promise);
            } catch (Throwable t) {
                notifyOutboundHandlerException(t, promise);
            }
        } else {
            deregister(promise);
        }
    }

    @Override
    public ChannelHandlerContext read() {
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_READ);
        EventExecutor executor = next.executor();
        if (executor.inEventLoop()) {
            next.invokeRead();
        } else {
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            executor.execute(tasks.invokeReadTask);
        }

        return this;
    }

    private void invokeRead() {
        if (invokeHandler()) {
            try {
                ((ChannelOutboundHandler) handler()).read(this);
            } catch (Throwable t) {
                invokeExceptionCaught(t);
            }
        } else {
            read();
        }
    }

    //从 AbstractChannelHandlerContext 抽象类继承
    @Override
    public ChannelFuture write(Object msg) {
        return write(msg, newPromise()); //创建 Promise 对象
    }

    @Override
    public ChannelFuture write(final Object msg, final ChannelPromise promise) {
        write(msg, false, promise);

        return promise;
    }

    void invokeWrite(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            invokeWrite0(msg, promise);
        } else {
            write(msg, promise);
        }
    }

    private void invokeWrite0(Object msg, ChannelPromise promise) {
        try {
            ((ChannelOutboundHandler) handler()).write(this, msg, promise);
        } catch (Throwable t) {
            notifyOutboundHandlerException(t, promise);
        }
    }

    @Override
    public ChannelHandlerContext flush() {
        // 获得下一个 Outbound 节点
        final AbstractChannelHandlerContext next = findContextOutbound(MASK_FLUSH);
        EventExecutor executor = next.executor();
        // 在 EventLoop 的线程中
        if (executor.inEventLoop()) {
            // 执行 flush 事件到下一个节点
            next.invokeFlush();
        } else {
            // 不在 EventLoop 的线程中
            // 创建 flush 任务
            Tasks tasks = next.invokeTasks;
            if (tasks == null) {
                next.invokeTasks = tasks = new Tasks(next);
            }
            // 提交到 EventLoop 的线程中，执行该任务
            safeExecute(executor, tasks.invokeFlushTask, channel().voidPromise(), null, false);
        }

        return this;
    }

    private void invokeFlush() {
        if (invokeHandler()) {
            invokeFlush0();
        } else {
            flush();
        }
    }

    private void invokeFlush0() {
        try {
            ((ChannelOutboundHandler) handler()).flush(this);
        } catch (Throwable t) {
            invokeExceptionCaught(t);
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        write(msg, true, promise);
        return promise;
    }

    void invokeWriteAndFlush(Object msg, ChannelPromise promise) {
        if (invokeHandler()) {
            // 执行 write 事件到下一个节点
            invokeWrite0(msg, promise);
            // 执行 flush 事件到下一个节点
            invokeFlush0();
        } else {
            writeAndFlush(msg, promise);
        }
    }

    //随着 write 或 writeAndFlush 事件不断的向下一个节点传播，最终会到达 HeadContext 节点
    private void write(Object msg, boolean flush, ChannelPromise promise) {
        // 消息( 数据 )为空，抛出异常
        ObjectUtil.checkNotNull(msg, "msg");
        try {
            // 判断是否为合法的 Promise 对象
            if (isNotValidPromise(promise, true)) {
                // 释放消息( 数据 )相关的资源
                ReferenceCountUtil.release(msg);
                // cancelled
                return;
            }
        } catch (RuntimeException e) {
            // 发生异常，释放消息( 数据 )相关的资源
            ReferenceCountUtil.release(msg);
            throw e;
        }
        // 获得下一个 Outbound 节点
        final AbstractChannelHandlerContext next = findContextOutbound(flush ?
                (MASK_WRITE | MASK_FLUSH) : MASK_WRITE);
        // 记录 Record 记录
        final Object m = pipeline.touch(msg, next);
        EventExecutor executor = next.executor();
        // 在 EventLoop 的线程中
        if (executor.inEventLoop()) {
            if (flush) {
                // 执行 writeAndFlush 事件到下一个节点
                next.invokeWriteAndFlush(m, promise);
            } else {
                // 执行 write 事件到下一个节点
                next.invokeWrite(m, promise);
            }
        } else {
            // 创建 write 任务
            final WriteTask task = WriteTask.newInstance(next, m, promise, flush);
            // 提交到 EventLoop 的线程中，执行该任务
            if (!safeExecute(executor, task, promise, m, !flush)) {
                // We failed to submit the WriteTask. We need to cancel it so we decrement the pending bytes
                // and put it back in the Recycler for re-use later.
                //
                // See https://github.com/netty/netty/issues/8343.
                task.cancel();
            }
        }
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return writeAndFlush(msg, newPromise());
    }

    // 通知 Outbound 事件的传播，发生异常
    private static void notifyOutboundHandlerException(Throwable cause, ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(channel(), executor());
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(channel(), executor());
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        ChannelFuture succeededFuture = this.succeededFuture;
        if (succeededFuture == null) {
            this.succeededFuture = succeededFuture = new SucceededChannelFuture(channel(), executor());
        }
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(channel(), executor(), cause);
    }

    private boolean isNotValidPromise(ChannelPromise promise, boolean allowVoidPromise) {
        ObjectUtil.checkNotNull(promise, "promise");

        if (promise.isDone()) {
            // Check if the promise was cancelled and if so signal that the processing of the operation
            // should not be performed.
            //
            // See https://github.com/netty/netty/issues/2349
            if (promise.isCancelled()) {
                return true;
            }
            throw new IllegalArgumentException("promise already done: " + promise);
        }

        if (promise.channel() != channel()) {
            throw new IllegalArgumentException(String.format(
                    "promise.channel does not match: %s (expected: %s)", promise.channel(), channel()));
        }

        if (promise.getClass() == DefaultChannelPromise.class) {
            return false;
        }

        if (!allowVoidPromise && promise instanceof VoidChannelPromise) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(VoidChannelPromise.class) + " not allowed for this operation");
        }

        if (promise instanceof AbstractChannel.CloseFuture) {
            throw new IllegalArgumentException(
                    StringUtil.simpleClassName(AbstractChannel.CloseFuture.class) + " not allowed in a pipeline");
        }
        return false;
    }

    //获得下一个 Inbound 节点的执行器
    private AbstractChannelHandlerContext findContextInbound(int mask) {
        // 循环，向后获得一个 Inbound 节点
        AbstractChannelHandlerContext ctx = this;
        EventExecutor currentExecutor = executor();
        do {
            ctx = ctx.next;
        } while (skipContext(ctx, currentExecutor, mask, MASK_ONLY_INBOUND));
        return ctx;
    }

    private AbstractChannelHandlerContext findContextOutbound(int mask) {
        // 循环，向前获得一个 Outbound 节点
        AbstractChannelHandlerContext ctx = this;
        EventExecutor currentExecutor = executor();
        do {
            ctx = ctx.prev;
        } while (skipContext(ctx, currentExecutor, mask, MASK_ONLY_OUTBOUND));
        return ctx;
    }

    private static boolean skipContext(
            AbstractChannelHandlerContext ctx, EventExecutor currentExecutor, int mask, int onlyMask) {
        // Ensure we correctly handle MASK_EXCEPTION_CAUGHT which is not included in the MASK_EXCEPTION_CAUGHT
        return (ctx.executionMask & (onlyMask | mask)) == 0 ||
                // We can only skip if the EventExecutor is the same as otherwise we need to ensure we offload
                // everything to preserve ordering.
                //
                // See https://github.com/netty/netty/issues/10067
                (ctx.executor() == currentExecutor && (ctx.executionMask & mask) == 0);
    }

    @Override
    public ChannelPromise voidPromise() {
        return channel().voidPromise();
    }

    final void setRemoved() {
        handlerState = REMOVE_COMPLETE;
    }

    final boolean setAddComplete() {
        for (;;) {
            int oldState = handlerState;
            if (oldState == REMOVE_COMPLETE) {
                return false;
            }
            // Ensure we never update when the handlerState is REMOVE_COMPLETE already.
            // oldState is usually ADD_PENDING but can also be REMOVE_COMPLETE when an EventExecutor is used that is not
            // exposing ordering guarantees.
            if (HANDLER_STATE_UPDATER.compareAndSet(this, oldState, ADD_COMPLETE)) {
                return true;
            }
        }
    }

    final void setAddPending() {
        boolean updated = HANDLER_STATE_UPDATER.compareAndSet(this, INIT, ADD_PENDING);
        assert updated; // This should always be true as it MUST be called before setAddComplete() or setRemoved().
    }

    final void callHandlerAdded() throws Exception {
        // We must call setAddComplete before calling handlerAdded. Otherwise if the handlerAdded method generates
        // any pipeline events ctx.handler() will miss them because the state will not allow it.
        if (setAddComplete()) {
            handler().handlerAdded(this);
        }
    }

    //这里会回调用户在Handler中自定义的handlerRemoved(ctx)方法
    final void callHandlerRemoved() throws Exception {
        try {
            // Only call handlerRemoved(...) if we called handlerAdded(...) before.
            if (handlerState == ADD_COMPLETE) {
                handler().handlerRemoved(this);
            }
        } finally {
            //将该节点的状态设置为removed
            // Mark the handler as removed in any case.
            setRemoved();
        }
    }

    /**
     * Makes best possible effort to detect if {@link ChannelHandler#handlerAdded(ChannelHandlerContext)} was called
     * yet. If not return {@code false} and if called or could not detect return {@code true}.
     *
     * If this method returns {@code false} we will not invoke the {@link ChannelHandler} but just forward the event.
     * This is needed as {@link DefaultChannelPipeline} may already put the {@link ChannelHandler} in the linked-list
     * but not called {@link ChannelHandler#handlerAdded(ChannelHandlerContext)}.
     */
    private boolean invokeHandler() {
        // Store in local variable to reduce volatile reads.
        int handlerState = this.handlerState;
        return handlerState == ADD_COMPLETE || (!ordered && handlerState == ADD_PENDING);
    }

    @Override
    public boolean isRemoved() {
        return handlerState == REMOVE_COMPLETE;
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        return channel().attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        return channel().hasAttr(key);
    }

    //如果不在 EventLoop 的线程中，会调用 #safeExecute()方法，提交到EventLoop的线程中执行
    private static boolean safeExecute(EventExecutor executor, Runnable runnable,
            ChannelPromise promise, Object msg, boolean lazy) {
        try {
            // 提交 EventLoop 的线程中，进行执行任务
            if (lazy && executor instanceof AbstractEventExecutor) {
                ((AbstractEventExecutor) executor).lazyExecute(runnable);
            } else {
                executor.execute(runnable);
            }
            return true;
        } catch (Throwable cause) {
            try {
                // 释放 msg 相关的资源
                if (msg != null) {
                    ReferenceCountUtil.release(msg);
                }
            } finally {
                // 发生异常，回调通知 promise 相关的异常
                promise.setFailure(cause);
            }
            return false;
        }
    }

    @Override
    public String toHintString() {
        return '\'' + name + "' will handle the message from this point.";
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ChannelHandlerContext.class) + '(' + name + ", " + channel() + ')';
    }

    static final class WriteTask implements Runnable {
        private static final ObjectPool<WriteTask> RECYCLER = ObjectPool.newPool(new ObjectCreator<WriteTask>() {
            @Override
            public WriteTask newObject(Handle<WriteTask> handle) {
                return new WriteTask(handle); // 创建 WriteTask 对象
            }
        });

        static WriteTask newInstance(AbstractChannelHandlerContext ctx,
                Object msg, ChannelPromise promise, boolean flush) {
            // 从 Recycler 的对象池中获得 WriteTask 对象
            WriteTask task = RECYCLER.get();
            // 初始化 WriteTask 对象的属性
            init(task, ctx, msg, promise, flush);
            return task;
        }

        /**
         * 提交任务时，是否计算 AbstractWriteTask 对象的自身占用内存大小
         */
        private static final boolean ESTIMATE_TASK_SIZE_ON_SUBMIT =
                SystemPropertyUtil.getBoolean("io.netty.transport.estimateSizeOnSubmit", true);

        /**
         * 每个 AbstractWriteTask 对象自身占用内存的大小。
         * -XX:+UseCompressedOops（JDK 8下默认为启用）；在64位系统中使用32位系统下引用的大小，也就是说，在64系统下回压缩普通对象的指针大小以节约内存占用的大小。
         * +----------------------------+
         * |    obj header(12 BYTES)    |
         * +----------------------------+
         * |  4 ref fields(pre 4 BYTES) |
         * +-------------------------- -+
         * |  one int field (4 BYTES)   |
         * +----------------------------+
         */
        // Assuming compressed oops, 12 bytes obj header, 4 ref fields and one int field
        private static final int WRITE_TASK_OVERHEAD =
                SystemPropertyUtil.getInt("io.netty.transport.writeTaskSizeOverhead", 32);
        // Recycler 处理器。而 Recycler 是 Netty 用来实现对象池的工具类。在网络通信中，写入是非常频繁的操作，因此通过 Recycler 重用 AbstractWriteTask 对象，减少对象的频繁创建，降低 GC 压力，提升性能。
        private final Handle<WriteTask> handle;
        /**
         * pipeline 中的节点
         */
        private AbstractChannelHandlerContext ctx;
        /**
         * 消息( 数据 )
         */
        private Object msg;
        /**
         * Promise 对象
         */
        private ChannelPromise promise;
        /**
         * 对象大小
         */
        private int size; // sign bit controls flush

        @SuppressWarnings("unchecked")
        private WriteTask(Handle<? extends WriteTask> handle) {
            this.handle = (Handle<WriteTask>) handle;
        }

        //初始化 WriteTask对象
        protected static void init(WriteTask task, AbstractChannelHandlerContext ctx,
                                   Object msg, ChannelPromise promise, boolean flush) {
            task.ctx = ctx;
            task.msg = msg;
            task.promise = promise;
            // 计算 AbstractWriteTask 对象大小 <1>
            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                task.size = ctx.pipeline.estimatorHandle().size(msg) + WRITE_TASK_OVERHEAD;
                // 增加 ChannelOutboundBuffer 的 totalPendingSize 属性  <2> DefaultChannelPipeline#incrementPendingOutboundBytes()
                ctx.pipeline.incrementPendingOutboundBytes(task.size);
            } else {
                task.size = 0;
            }
            if (flush) {
                task.size |= Integer.MIN_VALUE;
            }
        }

        @Override
        public void run() {
            try {
                // 减少 ChannelOutboundBuffer 的 totalPendingSize 属性 <1>
                decrementPendingOutboundBytes();
                // 执行 write 事件到下一个节点
                if (size >= 0) {
                    ctx.invokeWrite(msg, promise);
                } else {
                    ctx.invokeWriteAndFlush(msg, promise);
                }
            } finally {
                recycle();
            }
        }

        void cancel() {
            try {
                decrementPendingOutboundBytes();
            } finally {
                recycle();
            }
        }

        private void decrementPendingOutboundBytes() {
            if (ESTIMATE_TASK_SIZE_ON_SUBMIT) {
                ctx.pipeline.decrementPendingOutboundBytes(size & Integer.MAX_VALUE);
            }
        }

        private void recycle() {
            // 置空，help gc
            // Set to null so the GC can collect them directly
            ctx = null;
            msg = null;
            promise = null;
            // 回收对象
            handle.recycle(this);
        }
    }

    private static final class Tasks {
        private final AbstractChannelHandlerContext next;
        private final Runnable invokeChannelReadCompleteTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelReadComplete();
            }
        };
        private final Runnable invokeReadTask = new Runnable() {
            @Override
            public void run() {
                next.invokeRead();
            }
        };
        private final Runnable invokeChannelWritableStateChangedTask = new Runnable() {
            @Override
            public void run() {
                next.invokeChannelWritabilityChanged();
            }
        };
        private final Runnable invokeFlushTask = new Runnable() {
            @Override
            public void run() {
                next.invokeFlush();
            }
        };

        Tasks(AbstractChannelHandlerContext next) {
            this.next = next;
        }
    }
}
