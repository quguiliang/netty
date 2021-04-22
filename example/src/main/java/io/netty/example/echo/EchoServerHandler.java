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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;

import java.util.Iterator;

/**
 * Handler implementation for the echo server.
 */
@Sharable
public class EchoServerHandler extends ChannelInboundHandlerAdapter {

    private ChannelGroup channelGroup;

    public EchoServerHandler(ChannelGroup channelGroup) {
        this.channelGroup = channelGroup;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
        Channel channel = ctx.channel();
        if (evt instanceof ChannelInputShutdownEvent) {
            System.out.println("------ChannelInputShutdownEvent---------");
            channel.close();//远程主机强制关闭连接
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        //ctx.write(msg);
        //((ByteBuf)msg).release();
        System.out.println("EchoServerHandler------------channelRead()");
        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        //ctx.flush();
        ctx.fireChannelReadComplete();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("Client "+ctx.channel().remoteAddress()+" is error!");
        // Close the connection when an exception is raised.
        //cause.printStackTrace();
        ctx.close();
    }

    byte[] HEART_DATA = {0x34, (byte) 0xf6, 0x34, (byte) 0xf6, 0x00, 0x00, 0x00, 0x00};
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        channelGroup.add(ctx.channel());
        printChannelGroup(channelGroup);
        System.out.println("Client "+ctx.channel().remoteAddress()+" is online!");
        ByteBuf hearBeat = Unpooled.directBuffer();
        hearBeat.writeBytes(HEART_DATA);
        ctx.writeAndFlush(hearBeat);
        //ctx.fireChannelActive();

        //从Pipeline链表中删除ChannelHandler
        ctx.pipeline().remove(this);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        System.out.println(ctx.channel().remoteAddress() + " channelInactive!");
        printChannelGroup(channelGroup);
        ctx.fireChannelInactive();
    }

    private void printChannelGroup(ChannelGroup channelGroup){
        System.out.println("---------channelGroup----------");
        Iterator<Channel> it = channelGroup.iterator();
        while (it.hasNext()){
            System.out.println(it.next().toString());
        }
        System.out.println("---------channelGroup----------");
    }
}
