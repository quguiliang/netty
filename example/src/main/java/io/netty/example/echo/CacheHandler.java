package io.netty.example.echo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

public class CacheHandler extends SimpleChannelInboundHandler<Object> {
    private String ip;
    private String port;


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        SocketAddress client = ctx.channel().remoteAddress();
        String[] strAddr = client.toString().split(":");
        this.ip = strAddr[0].substring(strAddr[0].indexOf("/"));
        this.port = strAddr[1];
    }

/*    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        String str = new String(byteBuf.array());
        System.out.println("CacheHandler------------channelRead():" + str);
        ctx.fireChannelRead(msg);
    }*/

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf byteBuf = (ByteBuf) msg;
        int len = byteBuf.readableBytes();

        String str = byteBuf.readCharSequence(len, StandardCharsets.UTF_8).toString();
        System.out.println("CacheHandler------------channelRead():" + str);
        //ctx.fireChannelRead(msg);
    }
}
