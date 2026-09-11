package com.yexin.xiangqi;

import java.io.*;
import java.util.*;

public final class OverlayChecks {
    private static int checks;
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static final BoardRecognizer.Geometry GEO=new BoardRecognizer.Geometry(7,382,700,1146);
    private static BoardRecognizer.Observation observed(EngineState state,boolean redBottom){return new BoardRecognizer.Observation(GEO,state.position.squares,redBottom,1);}
    public static void main(String[] args) throws Exception {
        try(PikafishClient engine=new PikafishClient()) {
            engine.start(new File(args[0]),new File(args[1]),1);
            EngineState start=engine.state(Collections.emptyList());
            final WdlProbabilities[] openingWdl={null};
            String first=engine.search(Collections.emptyList(),80,new PikafishClient.Ticket(),line->{
                WdlProbabilities value=WdlProbabilities.parse(line,true);if(value!=null)openingWdl[0]=value;
            });
            check(start.legalMoves.contains(first),"real engine opening");
            check(openingWdl[0]!=null&&openingWdl[0].win+openingWdl[0].draw+openingWdl[0].loss==1000,"real engine WDL");
            EngineState after=engine.state(Arrays.asList(first));
            String reply=after.legalMoves.iterator().next();
            EngineState afterReply=engine.state(Arrays.asList(first,reply));
            ScreenGame red=new ScreenGame();red.reset(observed(start,true),null);red.state=start;
            check(red.ourTurn(),"red moves first");
            check(red.initialFen==null,"opening preserves complete history");
            red.expect(first,after);
            check(red.moves().isEmpty(),"dispatch does not commit a move");
            check(red.observe(observed(start,true))==ScreenGame.Change.SAME,"missed tap stays pending");
            check(red.moves().isEmpty()&&red.hasPending(),"missed tap does not advance turn");
            char[] corrupt=start.position.squares.clone();corrupt[40]='P';
            BoardRecognizer.Observation bad=new BoardRecognizer.Observation(GEO,corrupt,true,1);
            check(red.observe(bad)==ScreenGame.Change.UNKNOWN,"unexpected board is rejected");
            check(red.hasPending()&&red.moves().isEmpty(),"bad frame cannot modify history");
            check(red.observe(observed(after,true))==ScreenGame.Change.PENDING_CONFIRMED,"screen confirmation commits own move");
            check(red.moves().equals(Arrays.asList(first))&&!red.hasPending(),"one committed move");
            check(!red.ourTurn(),"wait for opponent");
            check(red.observe(observed(after,true))==ScreenGame.Change.SAME,"repeated frame does not duplicate move");
            check(red.observe(observed(afterReply,true))==ScreenGame.Change.MOVED,"opponent reply matches legal move");
            check(red.moves().equals(Arrays.asList(first,reply))&&red.state==null,"opponent transition refreshes legal moves");
            red.state=engine.state(red.moves());check(red.ourTurn(),"red turn after opponent reply");
            List<String> copy=red.moves();copy.clear();check(red.moves().size()==2,"immutable transcript snapshot");

            ScreenGame fast=new ScreenGame();fast.reset(observed(start,true),null);fast.state=start;fast.expect(first,after);
            check(fast.observe(observed(afterReply,true))==ScreenGame.Change.PENDING_AND_REPLY,"fast opponent between capture frames");
            check(fast.moves().equals(Arrays.asList(first,reply)),"both moves retained in order");
            check(!fast.hasPending(),"pending cleared after fast reply");

            ScreenGame black=new ScreenGame();black.reset(observed(start,false),null);black.state=start;
            check(!black.ourTurn(),"black must wait");
            boolean refused=false;try{black.expect(first,after);}catch(IllegalStateException expected){refused=true;}
            check(refused,"cannot dispatch for the opponent");
            check(black.observe(observed(after,false))==ScreenGame.Change.MOVED,"black observes red opening");
            black.state=engine.state(black.moves());check(black.ourTurn(),"black may now move");
            check(black.observe(observed(after,true))==ScreenGame.Change.UNKNOWN,"orientation change blocks transition");
            black.expect(reply,afterReply);check(black.moves().size()==1,"black click not committed early");
            check(black.observe(observed(afterReply,false))==ScreenGame.Change.PENDING_CONFIRMED,"black click confirmed");

            ScreenGame middle=new ScreenGame();boolean unknownTurn=false;
            try{middle.reset(observed(after,true),null);}catch(IllegalArgumentException expected){unknownTurn=true;}
            check(unknownTurn,"midgame never guesses turn from a static board");
            middle.reset(observed(after,true),false);
            EngineState restored=engine.state(middle.initialFen,middle.moves());middle.state=restored;
            check(Arrays.equals(restored.position.squares,after.position.squares),"midgame FEN restoration");
            check(!restored.position.redTurn,"midgame black to move");
            String m=engine.search(middle.initialFen,middle.moves(),50,new PikafishClient.Ticket(),null);
            check(restored.legalMoves.contains(m),"midgame engine move legal");
            EngineState continued=engine.state(middle.initialFen,Arrays.asList(m));
            check(continued.position.redTurn,"midgame move counter handles black start");
            check(Arrays.equals(continued.position.squares,ScreenGame.apply(restored.position.squares,m)),"FEN move sequence consistent");

            int a0=Position.square('a','0'),i9=Position.square('i','9');
            check(Math.abs(GEO.x(a0,true)-53)<3&&Math.abs(GEO.y(a0,true)-1102)<4,"red coordinates match supplied screenshot");
            check(Math.abs(GEO.x(a0,false)-655)<3&&Math.abs(GEO.y(a0,false)-425)<3,"black coordinates reverse both axes");
            check(Math.abs(GEO.x(i9,false)-53)<3&&Math.abs(GEO.y(i9,false)-1102)<4,"black far corner coordinates");
        }
        System.out.println("PASS "+checks+" overlay checks: turn, screen confirmation, missed clicks, fast replies, FEN continuation, coordinates");
    }
}
