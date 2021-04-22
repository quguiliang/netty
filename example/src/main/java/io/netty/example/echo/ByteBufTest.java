package io.netty.example.echo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ByteBufTest {
    public static void main(String[] args) {
        ByteBuf msg = Unpooled.buffer();
        msg.writeByte(0xFF);
        msg.writeByte(0xFF);

        //readShort() : -1
        //readUnsignedShort() : 65535
        int protocolHeader = msg.readUnsignedShort();
        System.out.println(protocolHeader);

        msg.writeLong(Long.MAX_VALUE);
        long bbLong = msg.readLong();
        System.out.println(bbLong);
        System.out.println(Long.MAX_VALUE);
        System.out.println(Integer.MAX_VALUE);
        //9223372036854775807
        //System.out.println(msg.readLong());
        System.out.println("nanoTime():" + (nanoTime()));

    }

    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

}
