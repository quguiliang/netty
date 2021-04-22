package io.netty.example.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.*;

public class BatchFlushHandler extends ChannelOutboundHandlerAdapter {

    private CompositeByteBuf compositeByteBuf;
    /**
     * 是否使用 CompositeByteBuf 对象，用于数据写入
     **/
    private boolean preferComposite;

    private SingleThreadEventLoop eventLoop;

    private Channel.Unsafe unsafe;

    /**
     * 是否添加任务到 tailTaskQueue 队列中
     */
    private boolean hasAddTailTask = false;

    public BatchFlushHandler() {
        this(true);
    }

    public BatchFlushHandler(boolean preferComposite) {
        this.preferComposite = preferComposite;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        // 初始化 CompositeByteBuf 对象，如果开启 preferComposite 功能
        if (preferComposite) {
            compositeByteBuf = ctx.alloc().compositeBuffer();
        }
        eventLoop = (SingleThreadEventLoop) ctx.executor();
        unsafe = ctx.channel().unsafe();
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // 写入到 CompositeByteBuf 对象中
        if (preferComposite) {
            compositeByteBuf.addComponent(true, (ByteBuf) msg);
            // 普通写入
        } else {
            ctx.write(msg);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        // 通过 hasAddTailTask 有且仅有每个 EventLoop 执行循环( run )，只添加一次任务
        if (!hasAddTailTask) {
            hasAddTailTask = true;

            // 【重点】添加最终批量提交( flush )的任务
            // 【重点】添加最终批量提交( flush )的任务
            // 【重点】添加最终批量提交( flush )的任务
            eventLoop.executeAfterEventLoopIteration(() -> {
                if (preferComposite) {
                    ctx.writeAndFlush(compositeByteBuf).addListener(future -> compositeByteBuf = ctx.alloc()
                            .compositeBuffer());
                } else {
                    unsafe.flush();
                }

                // 重置 hasAddTailTask ，从而实现下个 EventLoop 执行循环( run )，可以再添加一次任务
                hasAddTailTask = false;
            });
        }
    }
}
