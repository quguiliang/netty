package io.netty.example.mynio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class NIOServer {
    Selector selector;
    ServerSocketChannel serverSocket;

    public static void main(String[] args) throws IOException {
        NIOServer nioServer = new NIOServer(9000);
        nioServer.run();
    }

    public NIOServer(int port) throws IOException {
        selector = Selector.open();
        serverSocket = ServerSocketChannel.open();
        serverSocket.socket().bind(new InetSocketAddress(port));
        serverSocket.configureBlocking(false);
        serverSocket.register(selector, SelectionKey.OP_ACCEPT);
    }

    public void run() {
        while (!Thread.interrupted()) {
            try {
                //阻塞等待事件
                selector.select();
                //事件列表
                Set selected = selector.selectedKeys();
                Iterator it = selected.iterator();
                while (it.hasNext()) {
                    dispatch((SelectionKey) it.next());
                    it.remove();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void dispatch(SelectionKey key) throws Exception {
        if (key.isAcceptable()) {
            //新连接建立，注册
            register(key);
            System.out.println("----------新连接建立，注册-----------");
        } else if (key.isReadable()) {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            ByteBuffer byteBuffer = ByteBuffer.allocate(128);
            int len = socketChannel.read(byteBuffer);
            //如果有数据，把数据打印出来
            if(len > 0){
                System.out.println("接收到消息：" + new String(byteBuffer.array()));
            }else if(len == -1){
                //如果客户端断开连接，关闭socket
                System.out.println("客户端断开连接");
                socketChannel.close();
            }
        } else if (key.isWritable()) {
            //写事件处理
            System.out.println("--------写事件处理----------");
        }
    }

    private void register(SelectionKey key) throws Exception {
        ServerSocketChannel server = (ServerSocketChannel) key
                .channel();
        // 获得和客户端连接的通道
        SocketChannel channel = server.accept();
        channel.configureBlocking(false);
        //客户端通道注册到selector 上
        channel.register(this.selector, SelectionKey.OP_READ);
    }
}
