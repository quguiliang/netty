package io.netty.example.mynio;

import org.jctools.queues.MpscChunkedArrayQueue;
import org.jctools.queues.MpscUnboundedArrayQueue;

import java.util.*;
import java.util.concurrent.*;

/**
 * 3、结果分析(一)：
 * 通过结果打印耗时可以明显看到MpscUnboundedArrayQueue耗时几乎大多数都是不超过0.1s的，这添加、删除的操作效率不是一般的高，这也难怪人家netty要舍弃自己写的队列框架了；
 * 4、结果分析(二)：
 * CompareQueueCosts代码里面我将ArrayList、LinkedList注释掉了，那是因为队列数量太大，List的操作太慢，效率低下，所以在大量并发的场景下，大家还是能避免则尽量避免，否则就遭殃了；
 */
public class CompareQueueCosts {
    /**
     * 生产者数量
     */
    private static int COUNT_OF_PRODUCER = 2;
    /**
     * 消费者数量
     */
    private static final int COUNT_OF_CONSUMER = 1;
    /**
     * 准备添加的任务数量值
     */
    private static final int COUNT_OF_TASK = 1 << 20;

    /**
     * 线程池对象
     */
    private static ExecutorService executorService;

    public static void main(String[] args) throws Exception {
        for (int i = 1; i < 7; i++) {
            COUNT_OF_PRODUCER = (int) Math.pow(2, i);
            executorService = Executors.newFixedThreadPool(COUNT_OF_PRODUCER * 2);
            int basePow = 8;
            int capacity = 0;
            for (int j = 1; j < 3; j++) {
                capacity = 1 << (basePow + i);
                System.out.print("Producers: " + COUNT_OF_PRODUCER + "\t\t");
                System.out.print("Consumers: " + COUNT_OF_CONSUMER + "\t\t");
                System.out.print("Capacity: " + capacity + "\t\t");
                System.out.print("LinkedBlockingQueue: " + testQueue(new LinkedBlockingQueue<Integer>(capacity), COUNT_OF_TASK) + "s" + "\t\t");
                System.out.print("MpscUnboundedArrayQueue: " + testQueue(new MpscUnboundedArrayQueue<Integer>(capacity), COUNT_OF_TASK) + "s" + "\t\t");
                System.out.print("MpscChunkedArrayQueue: " + testQueue(new MpscChunkedArrayQueue<Integer>(capacity), COUNT_OF_TASK) + "s" + "\t\t");

                System.out.print("ArrayList: " + testQueue(new ArrayList<Integer>(capacity), COUNT_OF_TASK) + "s" + "\t\t");
                System.out.print("LinkedList: " + testQueue(new LinkedList<Integer>(), COUNT_OF_TASK) + "s" + "\t\t");
                System.out.println();
            }
            System.out.println();

            executorService.shutdown();
        }
    }


    private static Double testQueue(final Collection<Integer> queue, final int taskCount) throws Exception {
        CompletionService<Long> completionService = new ExecutorCompletionService<Long>(executorService);

        long start = System.currentTimeMillis();
        for (int i = 0; i < COUNT_OF_PRODUCER; i++) {
            executorService.submit(new Producer(queue, taskCount / COUNT_OF_PRODUCER));
        }
        for (int i = 0; i < COUNT_OF_CONSUMER; i++) {
            completionService.submit((new Consumer(queue, taskCount / COUNT_OF_CONSUMER)));
        }

        for (int i = 0; i < COUNT_OF_CONSUMER; i++) {
            completionService.take().get();
        }

        long end = System.currentTimeMillis();
        return Double.parseDouble("" + (end - start)) / 1000;
    }

    public static class Producer implements Runnable {
        private Collection<Integer> queue;
        private int taskCount;

        public Producer(Collection<Integer> queue, int taskCount) {
            this.queue = queue;
            this.taskCount = taskCount;
        }

        @Override
        public void run() {
            // Queue队列
            if (this.queue instanceof Queue) {
                Queue<Integer> tempQueue = (Queue<Integer>) this.queue;
                while (this.taskCount > 0) {
                    if (tempQueue.offer(this.taskCount)) {
                        this.taskCount--;
                    } else {
                        // System.out.println("Producer offer failed.");
                    }
                }
            }
            // List列表
            else if (this.queue instanceof List) {
                List<Integer> tempList = (List<Integer>) this.queue;
                while (this.taskCount > 0) {
                    if (tempList.add(this.taskCount)) {
                        this.taskCount--;
                    } else {
                        // System.out.println("Producer offer failed.");
                    }
                }
            }
        }
    }

    public static class Consumer implements Callable<Long> {
        private Collection<Integer> queue;
        private int taskCount;

        public Consumer(Collection<Integer> queue, int taskCount) {
            this.queue = queue;
            this.taskCount = taskCount;
        }

        @Override
        public Long call() {
            // Queue队列
            if (this.queue instanceof Queue) {
                Queue<Integer> tempQueue = (Queue<Integer>) this.queue;
                while (this.taskCount > 0) {
                    if ((tempQueue.poll()) != null) {
                        this.taskCount--;
                    }
                }
            }
            // List列表
            else if (this.queue instanceof List) {
                List<Integer> tempList = (List<Integer>) this.queue;
                while (this.taskCount > 0) {
                    if (!tempList.isEmpty() && (tempList.remove(0)) != null) {
                        this.taskCount--;
                    }
                }
            }
            return 0L;
        }
    }
}
