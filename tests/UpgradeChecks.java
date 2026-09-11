package com.yexin.xiangqi;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests the production search/cancel path used for opponent-turn prethinking. */
public final class UpgradeChecks {
    static int checks;
    static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(PikafishClient engine=new PikafishClient()) {
            engine.start(new File(args[0]),new File(args[1]),4);
            EngineState initial=engine.state(Collections.emptyList());
            for(int threads:new int[]{1,2,4}) {
                engine.configureThreads(threads);
                for(int hash:new int[]{128,256}) {
                    engine.configureHash(hash);
                    AtomicBoolean wdl=new AtomicBoolean();
                    String m=engine.search(Collections.emptyList(),180,new PikafishClient.Ticket(),line->{
                        if(WdlProbabilities.parse(line,true)!=null)wdl.set(true);
                    });
                    check(initial.legalMoves.contains(m),"legal move at threads="+threads+" hash="+hash);
                    check(wdl.get(),"WDL retained at each configuration");
                }
            }
            try{engine.configureHash(123);throw new AssertionError("invalid hash accepted");}
            catch(IllegalArgumentException expected){checks++;}
            for(int round=0;round<8;round++) {
                engine.newGame();
                PikafishClient.Ticket ponder=new PikafishClient.Ticket();
                CountDownLatch evaluating=new CountDownLatch(1);
                Future<String> pending=worker.submit(()->engine.search(Collections.emptyList(),0,ponder,line->evaluating.countDown()));
                check(evaluating.await(25,TimeUnit.SECONDS),"infinite prethinking actually started");
                ponder.cancelled=true;engine.stop();
                try{pending.get(15,TimeUnit.SECONDS);throw new AssertionError("accepted a prethinking move");}
                catch(ExecutionException expected){check(expected.getCause() instanceof CancellationException,"prethinking bestmove discarded");}
                List<String> actual=Collections.singletonList(round%2==0?"b2e2":"h2e2");
                EngineState after=engine.state(actual);
                check(!after.position.redTurn,"actual opponent move determines turn");
                String reply=engine.search(actual,150,new PikafishClient.Ticket(),null);
                check(after.legalMoves.contains(reply),"reply uses actual board, not speculative result");
                check(engine.state(Collections.emptyList()).position.fen.equals(initial.position.fen),"reset has no stale output");
            }
            PikafishClient.Ticket paused=new PikafishClient.Ticket();paused.cancelled=true;
            try{engine.search(Collections.emptyList(),0,paused,null);throw new AssertionError("started paused search");}
            catch(CancellationException expected){checks++;}
            check(engine.state(Collections.emptyList()).legalMoves.size()==44,"cancel before start leaves engine usable");
            System.out.println("PASS "+checks+" upgrade checks: threads/hash/WDL/prethink cancellation/actual-position transitions");
        } finally {worker.shutdownNow();}
    }
}
