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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.internal.PlatformDependent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        //配置SSL
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server.
        //创建两个EventGroup 对象
        EventLoopGroup bossGroup = new NioEventLoopGroup(1); //创建boss线程组，用于服务端接受客户端的连接
        EventLoopGroup workerGroup = new NioEventLoopGroup(); //创建worker线程组，用于进行SocketChannel的数据读写

        ChannelGroup channelGroup = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE);

        //创建EchoServerHandler对象
        final EchoServerHandler serverHandler = new EchoServerHandler(channelGroup);
        try {
            //Import 1
            //创建ServerBootstrap
            ServerBootstrap b = new ServerBootstrap();
            //Import 2
            b.group(bossGroup, workerGroup) //设置使用的EventLoopGroup
             .channel(NioServerSocketChannel.class) //设置要被实例化为NioServerSocketChannel类
             .option(ChannelOption.SO_BACKLOG, 100) //设置NioServerSocketChannel的可选项
             .handler(new LoggingHandler(LogLevel.TRACE)) //设置NioServerSocketChannel 的处理器
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception { //设置连入服务端的client的SocketChannel的处理器
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast("sslCtx",sslCtx.newHandler(ch.alloc()));
                     }
                     p.addLast(new LoggingHandler(LogLevel.DEBUG));
                     //p.addLast("serverHandler", serverHandler);
                     //p.addLast("cacheHandler", new CacheHandler());
                 }
             });

            //Import 3
            // Start the server.
            //绑定端口，并同步等待成功，即启动服务端
            ChannelFuture f = b.bind(PORT).addListener(new ChannelFutureListener() { // <1> 监听器就是我！
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.isSuccess()) {
                        System.out.println("Tcp server started!");
                    } else {
                        System.out.println("Tcp server start failed!");
                        future.cause().printStackTrace();
                    }
                }
            }).sync();

            //todo test netty direct memory
            //printNettyDirectMemory();
            //todo test netty direct memory

            //print workers
            //printWorkers(workerGroup);

            // Wait until the server socket is closed.
            //监听服务端关闭，并阻塞等待
            f.channel().closeFuture().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            //优雅关闭两个EventLoopGroup对象
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static void printNettyDirectMemory(){
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                System.out.println("netty use: " + PlatformDependent.usedDirectMemory()+" B");
            }
        },500,1000, TimeUnit.MILLISECONDS);
    }

    private static void printWorkers(EventLoopGroup workers){
        for(EventExecutor executor : workers){
            System.out.println(executor);
        }
    }
}
