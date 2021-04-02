package io.netty.example.mynio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BioServer {
    private static ExecutorService executorService = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        BioServer server = new BioServer();
        server.run();
    }

    //主线程连接
    public void run() {
        try {
            ServerSocket serverSocket = new ServerSocket(9000);
            while (true) {
                Socket socket = serverSocket.accept();
                //提交线程池处理
                executorService.execute(new Handler(socket));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    class Handler implements Runnable {
        Socket client;

        public Handler(Socket client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                //获取Socket的输入流，接收数据
                //输入的消息需要增加回车代表一行结束
                BufferedReader buf = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String readData = buf.readLine();
                while (readData != null) {
                    readData = buf.readLine();
                    System.out.println(readData);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
