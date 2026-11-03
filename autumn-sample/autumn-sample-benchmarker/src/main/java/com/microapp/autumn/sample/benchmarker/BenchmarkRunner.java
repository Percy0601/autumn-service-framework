package com.microapp.autumn.sample.benchmarker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TMultiplexedProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.layered.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import com.microapp.autumn.sample.api.SomeService;

/**
 * thrift vs http 协议对比压测工具。
 *
 * 用法:
 *   java -jar autumn-sample-benchmarker.jar \
 *        --thrift-host 127.0.0.1 --thrift-port 30880 \
 *        --http-url http://127.0.0.1:8083/echo?msg=hello \
 *        --threads 1,8,16,32 --requests 20000
 *
 * 口径:
 *   - 每个线程持有独立连接(thrift 独立 TSocket, http 独立 HttpClient/keep-alive), 全程复用, 公平对比;
 *   - 每轮先预热 requests 的 10% 后正式计时;
 *   - 吞吐 = 请求总数 / 墙钟耗时; 延迟分位基于单次调用耗时(纳秒)排序。
 */
public class BenchmarkRunner {

    private static final String THRIFT_METHOD = "echo(\"hello\")";

    public static void main(String[] args) throws Exception {
        String thriftHost = "127.0.0.1";
        int thriftPort = 30880;
        String httpUrl = "http://127.0.0.1:8083/echo?msg=hello";
        List<Integer> threads = Arrays.asList(1, 8, 16, 32);
        int requests = 20000;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--thrift-host" -> thriftHost = args[++i];
                case "--thrift-port" -> thriftPort = Integer.parseInt(args[++i]);
                case "--http-url" -> httpUrl = args[++i];
                case "--threads" -> threads = parseThreads(args[++i]);
                case "--requests" -> requests = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        System.out.println("== benchmark config ==");
        System.out.println("thrift: " + thriftHost + ":" + thriftPort + " -> " + THRIFT_METHOD);
        System.out.println("http  : " + httpUrl);
        System.out.println("threads=" + threads + ", requests/round=" + requests);

        // 单线程预热, 各协议 200 次, 确保连接与服务端 JIT 就绪
        System.out.println("== warmup ==");
        warmupThrift(thriftHost, thriftPort, 200);
        warmupHttp(httpUrl, 200);

        List<String> header = Arrays.asList(
                "协议", "并发", "总请求", "成功率", "QPS", "avg(ms)", "p50(ms)", "p95(ms)", "p99(ms)", "max(ms)");
        List<List<String>> rows = new ArrayList<>();
        List<Result> thriftResults = new ArrayList<>();
        List<Result> httpResults = new ArrayList<>();

        for (int t : threads) {
            Result thrift = runThrift(thriftHost, thriftPort, t, requests);
            thriftResults.add(thrift);
            rows.add(thrift.toRow("thrift", t, requests));
            Result http = runHttp(httpUrl, t, requests);
            httpResults.add(http);
            rows.add(http.toRow("http", t, requests));
        }

        System.out.println();
        System.out.println("== results ==");
        printTable(header, rows);

        StringBuilder sb = new StringBuilder();
        sb.append(printTableToString(header, rows));
        sb.append("\n\nsummary:\n");
        for (int i = 0; i < threads.size(); i++) {
            Result t = thriftResults.get(i);
            Result h = httpResults.get(i);
            double ratio = t.qps / h.qps;
            sb.append(String.format("threads=%s: thrift QPS %.0f vs http QPS %.0f, 快 %.2fx; p50 thrift %.3fms vs http %.3fms%n",
                    threads.get(i), t.qps, h.qps, ratio, t.p50Ms, h.p50Ms));
        }
        String report = sb.toString();
        System.out.println(report);

        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/benchmark_result.txt"), report);
            System.out.println("report written to /tmp/benchmark_result.txt");
        } catch (IOException e) {
            System.err.println("skip writing result file: " + e.getMessage());
        }
    }

    // ---------- thrift ----------

    private static Result runThrift(String host, int port, int threads, int totalRequests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        int perThread = totalRequests / threads;

        long startRef = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                // 服务端为 TThreadedSelectorServer + TMultiplexedProcessor + TFramedTransport + TBinaryProtocol，
                // 客户端需一致（TMultiplexedProtocol 的 serviceName 为注册名:接口全限定名）
                try (TTransport transport = new TFramedTransport(new TSocket(host, port))) {
                    transport.open();
                    TMultiplexedProtocol protocol = new TMultiplexedProtocol(
                            new TBinaryProtocol(transport), "com.microapp.autumn.sample.api.SomeService$Iface");
                    SomeService.Client client = new SomeService.Client(protocol);
                    ready.countDown();
                    go.await();
                    for (int n = 0; n < perThread; n++) {
                        long t0 = System.nanoTime();
                        try {
                            client.echo("hello");
                            latencies.add(new long[]{System.nanoTime() - t0});
                        } catch (TException e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        long wallStart = System.nanoTime();
        go.countDown();
        done.await();
        long wallNanos = System.nanoTime() - wallStart;
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        return new Result(totalRequests, errors.get(), wallNanos, latencies);
    }

    private static void warmupThrift(String host, int port, int count) throws Exception {
        try (TTransport transport = new TFramedTransport(new TSocket(host, port))) {
            transport.open();
            TMultiplexedProtocol protocol = new TMultiplexedProtocol(
                    new TBinaryProtocol(transport), "com.microapp.autumn.sample.api.SomeService$Iface");
            SomeService.Client client = new SomeService.Client(protocol);
            for (int i = 0; i < count; i++) {
                client.echo("hello");
            }
        } catch (Exception e) {
            System.err.println("thrift warmup failed: " + e.getMessage());
        }
    }

    // ---------- http ----------

    private static Result runHttp(String url, int threads, int totalRequests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<long[]> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicLong errors = new AtomicLong();
        int perThread = totalRequests / threads;

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .version(HttpClient.Version.HTTP_1_1)
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                ready.countDown();
                try {
                    go.await();
                    for (int n = 0; n < perThread; n++) {
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                            if (resp.statusCode() != 200) {
                                errors.incrementAndGet();
                            } else {
                                latencies.add(new long[]{System.nanoTime() - t0});
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        long wallStart = System.nanoTime();
        go.countDown();
        done.await();
        long wallNanos = System.nanoTime() - wallStart;
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        return new Result(totalRequests, errors.get(), wallNanos, latencies);
    }

    private static void warmupHttp(String url, int count) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            for (int i = 0; i < count; i++) {
                client.send(request, HttpResponse.BodyHandlers.ofString());
            }
        } catch (Exception e) {
            System.err.println("http warmup failed: " + e.getMessage());
        }
    }

    // ---------- stats ----------

    private static List<Integer> parseThreads(String s) {
        List<Integer> list = new ArrayList<>();
        for (String part : s.split(",")) {
            list.add(Integer.parseInt(part.trim()));
        }
        return list;
    }

    private static long percentile(List<long[]> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)))[0];
    }

    private static void printTable(List<String> header, List<List<String>> rows) {
        System.out.println(printTableToString(header, rows));
    }

    private static String printTableToString(List<String> header, List<List<String>> rows) {
        List<List<String>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        int[] widths = new int[header.size()];
        for (List<String> row : all) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (List<String> row : all) {
            StringBuilder line = new StringBuilder("| ");
            for (int i = 0; i < row.size(); i++) {
                line.append(String.format("%-" + widths[i] + "s", row.get(i))).append(" | ");
            }
            sb.append(line.toString().trim()).append('\n');
            if (row == header) {
                StringBuilder sep = new StringBuilder("|");
                for (int w : widths) {
                    sep.append("-".repeat(w + 2)).append("|");
                }
                sb.append(sep).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------- result ----------

    private static final class Result {
        final long total;
        final long errors;
        final long wallNanos;
        final List<long[]> latencies;
        final double qps;
        final double avgMs;
        final double p50Ms;
        final double p95Ms;
        final double p99Ms;
        final double maxMs;

        Result(long total, long errors, long wallNanos, List<long[]> latencies) {
            this.total = total;
            this.errors = errors;
            this.wallNanos = wallNanos;
            this.latencies = latencies;
            this.qps = total / (wallNanos / 1_000_000_000.0);
            double sum = 0;
            long max = 0;
            for (long[] l : latencies) {
                sum += l[0];
                max = Math.max(max, l[0]);
            }
            this.avgMs = latencies.isEmpty() ? 0 : sum / latencies.size() / 1_000_000.0;
            this.maxMs = max / 1_000_000.0;
            long[] sorted = latencies.stream().mapToLong(l -> l[0]).sorted().toArray();
            List<long[]> boxed = new ArrayList<>();
            for (long v : sorted) {
                boxed.add(new long[]{v});
            }
            this.p50Ms = percentile(boxed, 50) / 1_000_000.0;
            this.p95Ms = percentile(boxed, 95) / 1_000_000.0;
            this.p99Ms = percentile(boxed, 99) / 1_000_000.0;
        }

        List<String> toRow(String proto, int threads, int totalRequests) {
            return Arrays.asList(
                    proto,
                    String.valueOf(threads),
                    String.valueOf(totalRequests),
                    String.format("%.2f%%", (total - errors) * 100.0 / total),
                    String.format("%.0f", qps),
                    String.format("%.3f", avgMs),
                    String.format("%.3f", p50Ms),
                    String.format("%.3f", p95Ms),
                    String.format("%.3f", p99Ms),
                    String.format("%.3f", maxMs));
        }

        static Result fromRow(List<String> row) {
            double successRate = Double.parseDouble(row.get(3).replace("%", "")) / 100;
            long total = Long.parseLong(row.get(2));
            Result r = new Result(total, (long) (total * (1 - successRate)), 1, new ArrayList<>());
            return r;
        }
    }
}
