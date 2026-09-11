package com.yexin.xiangqi;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public final class CoreChecks {
    private static int checks;
    private static void check(boolean condition,String name) {
        if(!condition)throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("engine model");
        PikafishClient client=new PikafishClient();
        try {
            client.start(new File(args[0]),new File(args[1]),1);
            EngineState start=client.state(Collections.emptyList());
            check(start.legalMoves.size()==44,"opening has 44 legal moves");
            check(start.position.redTurn,"red always starts");
            check(start.position.squares[Position.from("a0a1")]=='R',"red coordinate origin");
            check(start.position.squares[Position.from("i9i8")]=='r',"black coordinate origin");
            check(start.position.notation("h2e2").equals("炮二平五"),"red cannon notation");
            check(start.position.notation("b0c2").equals("马八进七"),"horse notation");
            check(start.position.notation("a9a8").equals("车1进1"),"black notation");
            for(int s=0;s<90;s++)check(Position.square(Position.coordinate(s).charAt(0),Position.coordinate(s).charAt(1))==s,"coordinate roundtrip "+s);
            for(boolean humanRed:new boolean[]{true,false}) {
                client.newGame();
                GameSession game=new GameSession();game.reset(humanRed);
                check(game.humanTurn()==humanRed,"side selection "+humanRed);
                check(!game.canUndo(),"no undo without human move");
                EngineState current=start;
                if(!humanRed) {
                    String opening=client.search(game.moves(),150,new PikafishClient.Ticket(),null);
                    check(current.legalMoves.contains(opening),"AI first move legal");
                    game.append(opening,current);current=client.state(game.moves());
                    check(game.humanTurn(),"AI first then human black");
                    check(!game.canUndo(),"do not undo the AI's initial move alone");
                }
                int beforeHuman=game.size();
                String human=current.legalMoves.iterator().next();
                game.append(human,current);current=client.state(game.moves());
                check(!game.humanTurn(),"human move hands turn to AI");
                List<String> immutableRequest=game.moves();
                String ai=client.search(game.moves(),150,new PikafishClient.Ticket(),null);
                game.append(ai,current);current=client.state(game.moves());
                check(game.humanTurn(),"AI response hands turn to human");
                check(immutableRequest.size()==beforeHuman+1,"request copy stable after changes");
                GameSession restored=new GameSession();
                restored.restore(humanRed,game.serializeMoves(),game.serializeNotes());
                check(restored.moves().equals(game.moves()),"transcript persistence");
                check(restored.notes().equals(game.notes()),"notation persistence");
                check(client.state(restored.moves()).position.fen.equals(current.position.fen),"resume exact position");
                check(restored.undo(),"undo available");
                check(restored.size()==beforeHuman && restored.humanTurn(),"undo returns to human decision");
                check(!client.state(restored.moves()).finished(),"undo position playable");
                restored.append(human,client.state(restored.moves()));
                check(restored.undo() && restored.size()==beforeHuman,"undo while AI would be thinking");
            }
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                PikafishClient.Ticket cancel=new PikafishClient.Ticket();
                Future<String> future=pool.submit(() -> client.search(Collections.emptyList(),0,cancel,null));
                Thread.sleep(180);
                cancel.cancelled=true;client.stop();
                try {future.get(15,TimeUnit.SECONDS);throw new AssertionError("cancelled search returned an accepted move");}
                catch(ExecutionException expected){check(expected.getCause() instanceof CancellationException,"cancel drops result");}
                EngineState fresh=client.state(Collections.emptyList());
                check(fresh.position.fen.equals(start.position.fen),"reset after cancellation");
                PikafishClient.Ticket force=new PikafishClient.Ticket();force.stopNow=true;
                String move=client.search(Collections.emptyList(),0,force,null);
                check(fresh.legalMoves.contains(move),"immediate stop still returns legal move");
                check(client.state(Collections.emptyList()).legalMoves.size()==44,"no stale output after force move");
            } finally {pool.shutdownNow();}
            // New upstream fails closed and exits on an illegal position command.
            // Keep this last: reusing a terminated engine would hide the stricter contract.
            boolean rejected=false;
            try {client.state(Arrays.asList("a0a9"));}catch(Exception expected){rejected=true;}
            check(rejected,"reject invalid stored transcript instead of silently truncating");
            System.out.println("PASS "+checks+" checks: sides, moves, notation, undo, persistence, cancel, immediate stop");
        } finally {client.close();}
    }
}
