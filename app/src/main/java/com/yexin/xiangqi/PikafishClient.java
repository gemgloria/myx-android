/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Serialized requests over UCI. stop() is the sole concurrent command. */
public final class PikafishClient implements Closeable {
    public interface Progress { void update(String line); }
    public static final class Ticket {
        public volatile boolean cancelled;
        public volatile boolean stopNow;
    }
    private final BlockingQueue<String> output = new LinkedBlockingQueue<>();
    private static final String EOF = "\u0000ENGINE_EXITED";
    private final Object writeLock = new Object();
    private Process process;
    private BufferedWriter writer;
    private volatile boolean closed;
    private int configuredThreads;
    private int configuredHash;
    public String engineName = "Pikafish 2026-09-06";

    public void start(File executable, File model, int threads) throws Exception {
        start(executable,model,threads,256);
    }

    public void start(File executable, File model, int threads, int hashMegabytes) throws Exception {
        if (!executable.isFile() || !model.isFile()) throw new IOException("引擎或模型文件缺失");
        synchronized (writeLock) {
            if (closed) throw new IOException("引擎已关闭");
            process = new ProcessBuilder(executable.getAbsolutePath())
                .directory(model.getParentFile()).redirectErrorStream(true).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) output.offer(line);
            } catch (IOException ignored) { }
            finally { output.offer(EOF); }
        }, "pikafish-output");
        reader.setDaemon(true);
        reader.start();
        send("uci");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (;;) {
            String line = next(deadline);
            if (line.startsWith("id name ")) engineName = line.substring(8);
            if (line.equals("uciok")) break;
        }
        send("setoption name EvalFile value " + model.getAbsolutePath());
        configureHash(hashMegabytes);
        send("setoption name MultiPV value 1");
        send("setoption name UCI_ShowWDL value true");
        send("setoption name Ponder value false");
        send("setoption name NumaPolicy value none");
        configureThreads(threads);
        ready();
    }

    public void configureThreads(int threads) throws Exception {
        threads = Math.max(1, Math.min(4, threads));
        if (configuredThreads != threads) {
            send("setoption name Threads value " + threads);
            configuredThreads = threads;
            ready();
        }
    }

    /** Call only on the serialized engine worker while no search is running. */
    public void configureHash(int megabytes) throws Exception {
        if (megabytes != 128 && megabytes != 256 && megabytes != 512)
            throw new IllegalArgumentException("缓存仅支持 128/256/512 MB");
        if (configuredHash != megabytes) {
            send("setoption name Hash value " + megabytes);
            configuredHash = megabytes;
            ready();
        }
    }

    public void newGame() throws Exception { send("ucinewgame"); ready(); }

    private void setPosition(String initialFen,List<String> moves) throws IOException {
        if(initialFen!=null) {
            new Position(initialFen);
            if(initialFen.contains("\n")||initialFen.contains("\r")||initialFen.split("\\s+").length!=6)
                throw new IOException("无效初始局面");
        }
        StringBuilder s = new StringBuilder(initialFen==null?"position startpos":"position fen "+initialFen);
        if (!moves.isEmpty()) {
            s.append(" moves");
            for (String m : moves) {
                if (!Position.validMove(m)) throw new IOException("无效棋谱着法");
                s.append(' ').append(m);
            }
        }
        send(s.toString());
    }

    public EngineState state(List<String> moves) throws Exception {
        return state(null,moves);
    }
    public EngineState state(String initialFen,List<String> moves) throws Exception {
        ready();
        setPosition(initialFen,moves);
        send("appstate");
        String fen = null, result = null, reason = null;
        boolean check = false, version = false;
        Set<String> legal = new LinkedHashSet<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (;;) {
            String line = next(deadline);
            if (line.equals("appstate version 1")) version = true;
            else if (line.startsWith("appstate fen ")) fen = line.substring(13);
            else if (line.startsWith("appstate result ")) result = line.substring(16);
            else if (line.startsWith("appstate reason ")) reason = line.substring(16);
            else if (line.equals("appstate check 1")) check = true;
            else if (line.startsWith("appstate moves")) {
                String tail = line.substring(14).trim();
                if (!tail.isEmpty()) legal.addAll(Arrays.asList(tail.split(" +")));
            } else if (line.equals("appstateok")) break;
        }
        if (!version || fen == null || result == null || reason == null)
            throw new IOException("引擎局面信息不完整");
        String[] fields = fen.split("\\s+");
        int initialPly=0;
        if(initialFen!=null) {
            String[] initial=initialFen.split("\\s+");
            initialPly=(Integer.parseInt(initial[5])-1)*2+(initial[1].equals("b")?1:0);
        }
        if (fields.length < 6 || (Integer.parseInt(fields[5])-1)*2+(fields[1].equals("b")?1:0) != initialPly+moves.size())
            throw new IOException("保存的棋谱未能完整恢复，请开始新对局");
        return new EngineState(fen, legal, result, reason, check);
    }

    public String search(List<String> moves, int millis, Ticket ticket, Progress progress) throws Exception {
        return search(null,moves,millis,ticket,progress);
    }
    public String search(String initialFen,List<String> moves, int millis, Ticket ticket, Progress progress) throws Exception {
        if (ticket.cancelled) throw new CancellationException();
        ready();
        setPosition(initialFen,moves);
        if (ticket.cancelled) throw new CancellationException();
        send(millis == 0 ? "go infinite" : "go movetime " + millis);
        boolean stopping = false;
        long stopAt = 0;
        long hardDeadline = millis == 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis + 20000L);
        for (;;) {
            if ((ticket.cancelled || ticket.stopNow || System.nanoTime() > hardDeadline) && !stopping) {
                stop();
                stopping = true;
                stopAt = System.nanoTime();
            }
            if (stopping && System.nanoTime() - stopAt > TimeUnit.SECONDS.toNanos(12))
                throw new IOException("引擎未能停止，请重新打开应用");
            String line = output.poll(120, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if (line.equals(EOF)) throw new IOException("引擎意外退出");
            if (line.startsWith("bestmove ")) {
                if (ticket.cancelled) throw new CancellationException();
                String[] parts = line.split("\\s+");
                return parts.length > 1 ? parts[1] : "0000";
            }
            if (line.startsWith("info depth ") && progress != null && !ticket.cancelled) progress.update(line);
            if (line.contains("ERROR:") || line.contains("Error:")) throw new IOException(line);
        }
    }

    private void ready() throws Exception {
        send("isready");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!next(deadline).equals("readyok")) { }
    }
    private String next(long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("等待引擎响应超时");
        String line = output.poll(remaining, TimeUnit.NANOSECONDS);
        if (line == null) throw new IOException("等待引擎响应超时");
        if (line.equals(EOF)) throw new IOException("引擎意外退出");
        if (line.contains("CRITICAL ERROR")) throw new IOException(line);
        return line;
    }
    private void send(String command) throws IOException {
        synchronized (writeLock) {
            if (closed || writer == null) throw new IOException("引擎尚未就绪");
            writer.write(command);
            writer.newLine();
            writer.flush();
        }
    }
    public void stop() {
        try { send("stop"); } catch (IOException ignored) { }
    }
    @Override public void close() {
        synchronized (writeLock) {
            if (closed) return;
            try { send("quit"); } catch (IOException ignored) { }
            closed = true;
            if (process != null) process.destroy();
        }
        output.offer(EOF);
    }
}
