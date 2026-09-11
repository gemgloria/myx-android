#!/usr/bin/env python3
"""Apply only the MYX appstate bridge to the pinned, otherwise unmodified engine."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BRIDGE="// Added for the offline Android GUI, 2026-09-03. Search and evaluation are unchanged.\n// Called only while the search is idle; full move history is retained by set_position.\nstd::string Engine::app_state() {\n    wait_for_search_finished();\n    MoveList<LEGAL> legal(pos);\n    std::string result = \"ongoing\", reason = \"none\";\n    bool check = bool(pos.checkers());\n    if (legal.size() == 0)\n    {\n        result = pos.side_to_move() == WHITE ? \"black\" : \"red\";\n        reason = check ? \"checkmate\" : \"stalemate\";\n    }\n    else\n    {\n        Value ruleValue = VALUE_DRAW;\n        if (pos.rule_judge(ruleValue, 0))\n        {\n            reason = \"rule\";\n            if (ruleValue == VALUE_DRAW)\n                result = \"draw\";\n            else\n            {\n                Color winner = ruleValue > VALUE_DRAW ? pos.side_to_move() : ~pos.side_to_move();\n                result = winner == WHITE ? \"red\" : \"black\";\n            }\n        }\n    }\n    std::ostringstream out;\n    out << \"appstate version 1\\nappstate fen \" << pos.fen()\n        << \"\\nappstate result \" << result << \"\\nappstate reason \" << reason\n        << \"\\nappstate check \" << (check ? 1 : 0) << \"\\nappstate moves\";\n    for (const auto& move : legal)\n        out << ' ' << UCIEngine::move(move);\n    out << \"\\nappstateok\";\n    return out.str();\n}\n"
def change(file,old,new):
    p=ROOT/'third_party/pikafish/src'/file
    text=p.read_text()
    if text.count(old)!=1: raise SystemExit('Patch anchor mismatch: '+file)
    p.write_text(text.replace(old,new))
change('engine.cpp','#include "misc.h"','#include "misc.h"\n#include "movegen.h"')
change('engine.cpp','namespace NN = Eval::NNUE;','namespace NN = Eval::NNUE;\n\n'+BRIDGE)
change('engine.h','    std::string                          fen() const;','    std::string                          app_state();\n    std::string                          fen() const;')
change('uci.cpp','        else if (token == "d")','        else if (token == "appstate")\n            sync_cout << engine.app_state() << sync_endl;\n        else if (token == "d")')

