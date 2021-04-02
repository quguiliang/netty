package io.netty.example.mynio.reactor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

/**
 * 单Reactor单线程模型
 * 其中Reactor线程，负责多路分离套接字，有新连接到来触发connect 事件之后，交由Acceptor进行处理，有IO读写事件之后交给hanlder 处理。
 */
public class Reactor implements Runnable {
    static ServerSocketChannel serverSocket;
    static Selector selector;

    private Reactor() throws ClosedChannelException {
        SelectionKey sk = serverSocket.register(selector, SelectionKey.OP_ACCEPT);
        //attach acceptor 处理新连接
        sk.attach(new Acceptor());
    }

    @Override
    public void run() {
        try {
            while (!Thread.interrupted()) {
                selector.select();
                Set selected = selector.selectedKeys();
                Iterator it = selected.iterator();
                while (it.hasNext()) {
                    //分发事件处理
                    dispatch((SelectionKey) it.next());
                    it.remove();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void dispatch(SelectionKey k) {
        // 若是连接事件获取是acceptor
        // 若是IO读写事件获取是handler
        Runnable runnable = (Runnable) k.attachment();
        if (runnable != null) {
            runnable.run();
        }
    }

    /**
     * 连接事件就绪，处理连接事件
     */
    class Acceptor implements Runnable {

        @Override
        public void run() {
            try {
                SocketChannel c = serverSocket.accept();
                if (c != null) {
                    new Handler(c, selector);
                }
            } catch (Exception e) {

            }
        }
    }

    class Handler {
        SocketChannel c;
        Selector selector;

        public Handler(SocketChannel c, Selector selector) {
            this.c = c;
            this.selector = selector;
        }
    }

    public static void main(String[] args) throws IOException {
        selector = Selector.open();
        serverSocket = ServerSocketChannel.open();
        serverSocket.socket().bind(new InetSocketAddress(9000));
        serverSocket.configureBlocking(false);
        new Reactor().run();
    }
}
