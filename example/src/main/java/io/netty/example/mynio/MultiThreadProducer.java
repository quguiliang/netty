package io.netty.example.mynio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class MultiThreadProducer {
    private static final int COUNT_OF_PRODUCER = 2;
    private static final int COUNT_OF_CONSUMER = 1;
    public static final int capacity = 512;
    private static ExecutorService executorService = Executors.newFixedThreadPool(COUNT_OF_PRODUCER * 2);

    public static void main(String[] args) throws InterruptedException {
        List<Integer> list = new ArrayList<>();
        long start = System.currentTimeMillis();

        new Thread(new TaskRun(list)).start();

        new Thread(new TaskRun(list)).start();

        long end = System.currentTimeMillis();
        System.out.println(Double.parseDouble("" + (end - start)) / 1000);
        Thread.sleep(5_000);
        System.out.println("list size:" + list.size());
    }

    private static class TaskRun implements Runnable {

        private List<Integer> list;

        public TaskRun(List<Integer> list) {
            this.list = list;
        }

        @Override
        public void run() {
            for (int i = 0; i < 1000; i++) {
                list.add(i);
                System.out.println(Thread.currentThread().getName() + " put data：" + i);
            }
        }
    }

    private static void test() throws InterruptedException, ExecutionException {
        long start = System.currentTimeMillis();

        CompletionService<Long> completionService = new ExecutorCompletionService<Long>(executorService);
        //List<Integer> list = new LinkedList<Integer>();
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < COUNT_OF_PRODUCER; i++) {
            executorService.submit(new CompareQueueCosts.Producer(list, capacity / COUNT_OF_PRODUCER));
        }

        for (int i = 0; i < COUNT_OF_CONSUMER; i++) {
            completionService.submit((new CompareQueueCosts.Consumer(list, capacity / COUNT_OF_CONSUMER)));
        }

        for (int i = 0; i < 1; i++) {
            completionService.take().get();
        }

        long end = System.currentTimeMillis();
        System.out.println(Double.parseDouble("" + (end - start)) / 1000);
    }
}
