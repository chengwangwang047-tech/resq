import domain.ComputeResource;
import domain.ResourceSlot;

import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Day 7 练习：通过线程池提交 100 个请求，争抢同一个资源时段。
 *
 * 请按顺序完成 TODO。遇到未完成的步骤时，程序会主动停止，
 * 避免将不完整的运行结果误认为真实实验结果。
 */
public final class Day7ReservationThreadPoolLab {

    private static final int REQUEST_COUNT = 100;
    private static final String AVAILABLE = "AVAILABLE";
    private static final String RESERVED = "RESERVED";

    private Day7ReservationThreadPoolLab() {
    }

    public static void main(String[] args) throws InterruptedException {
        ResourceSlot slot = newAvailableSlot();
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger successful = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        // 已接受的任务先在这里等待，直到提交循环结束。
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(REQUEST_COUNT);

        ThreadPoolExecutor executor = createExecutor();
        long startNanos = System.nanoTime();
        for (int requestId = 1; requestId <= REQUEST_COUNT; requestId++) {
            Runnable request = () -> {
                try {
                    start.await();
                    synchronized (slot) {
                        if (AVAILABLE.equals(slot.getStatus())) {
                            slot.setStatus(RESERVED);
                            // TODO 3：成功预约数加 1。
                            successful.getAndIncrement();
                        } else {
                            // TODO 3：失败预约数加 1。
                            failed.getAndIncrement();
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    // TODO 3：决定如何统计被中断的请求。
                    failed.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            };

            // TODO 2：将 request 提交给 executor，并统计这次提交。
            // 第一次运行时，队列容量应足以容纳全部 100 个请求，
            // 避免工作线程等待 start 时发生拒绝。
            // 如果改用较小的队列，需要处理拒绝，并为被拒绝的请求
            // 调用 finished.countDown()，否则 finished.await() 可能一直等待。
            // 此处使用 CallerRunsPolicy 也可能死锁：提交任务的 main 线程
            // 会亲自执行任务，而任务又在等待 main 线程释放 start。
            try {
                executor.submit(request);
                submitted.getAndIncrement();
            } catch (RejectedExecutionException e) {
                finished.countDown();
            }
        }

        // TODO 4：释放 start，等待 finished，然后正常关闭线程池。
        // 等线程池彻底结束后，再读取统计结果。
        // 完成后计算总耗时：
        // long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        // 输出 submitted、successful、failed、elapsedMillis 和最终状态。
        // 检查：如果 100 个请求均被接受，预期为 100 / 1 / 99 / RESERVED。
        start.countDown();
        finished.await();
        executor.shutdown();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        int rejected = REQUEST_COUNT - submitted.get();

        System.out.println(
                "submitted=" + submitted.get()
                        + ", successful=" + successful.get()
                        + ", failed=" + failed.get()
                        + ", rejected=" + rejected
                        + ", elapsedMillis=" + elapsedMillis
                        + ", status=" + slot.getStatus());

    }

    private static ThreadPoolExecutor createExecutor() {
        // TODO 1：返回一个 ThreadPoolExecutor。自行选择核心线程数、最大线程数、
        // 空闲线程存活时间、队列和拒绝策略。第一次运行建议使用
        // 容量 >= REQUEST_COUNT 的有界队列，以及 AbortPolicy。
        // 然后尝试调整队列容量，并解释行为为何变化
        ThreadPoolExecutor excutor = new ThreadPoolExecutor(2, 10, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(3),
                new ThreadPoolExecutor.AbortPolicy());
        return excutor;
    }

    private static ResourceSlot newAvailableSlot() {
        ComputeResource resource = new ComputeResource(
                "resource-day7", "A100", "GPU", "Day 7 线程池练习", true);
        return new ResourceSlot(
                "slot-day7", resource, "10:00", "11:00",
                LocalDate.of(2026, 9, 28), AVAILABLE);
    }
}
