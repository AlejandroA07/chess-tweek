# Game Review explanations: research and recommended method

Investigated on 2026-11-22. Covers chess apps, open-source tagger code, published research, opening data, and coaching literature, measured against Chess Tweek's stored analysis evidence (MultiPV 3 lines before and after each move, phase, severity, mate events) and the conservative `TacticDetector`. It informs Stage 8B (Deeper Game Review) and the Stage 10 opening context in `03-delivery-plan.md`. Source code was read to learn from, not copied; lichess-puzzler is AGPL-3.0 and En Croissant is GPL-3.0.

## Summary

- **No major app publishes how its written explanations are made.** Chess.com publishes its move-classification thresholds but credits an unexplained "explanation engine" for the coach text. Lichess, En Croissant, and ChessBase publish or document labels and lines, not reasons. Lichess's labels come only from changes in win chance.
- **The best open reference for named tactics is the lichess-puzzler tagger.** Each theme is a short geometric test over a forcing line. It works because every puzzle line was first checked to be forcing, with a single winning move. Chess Tweek's best line is not checked that way, so the same tests need an extra "the gain happens" proof, which the project already requires.
- **Research agrees that engines do not give human reasons.** Stockfish 16.1 removed its last hand-written evaluation. Large language models (LLMs) still invent facts: a 2026 benchmark found 22.0% of sub-claims wrong for GPT-5.4, a leading model, without tools, and over 40% for smaller open models.
- **Coaches agree on one method.** Analyse first without an engine, find the turning points, ask *why* each mistake happened, and only then check with the engine. Of the causes coaches name, the engine can measure only some (material, tactics, time used). Others, such as "I did not look at their checks", only the player can report.
- **Recommendation for Stage 8B:** keep the rule "a label only when the stored line proves it". Add motifs in this order: discovered attack and check, pin, skewer, removing the defender, trapped piece, deflection, in-between move. Add an "only move" signal from the MultiPV 3 gap. Add positional *observations* computed on matched positions, worded as "what changed", never as "why".
- **Error categories should have two axes.** The *engine-proven type* is automatic: missed tactic, allowed tactic, mate, positional, opening deviation, endgame. The *human cause* comes only from the owner or from the stored clocks: threat not seen, move not considered, miscalculated, misjudged, time pressure, opening not known.
- **Recommendation for Stage 10:** name each position from the CC0 Lichess opening table by EPD lookup. Mark where the game left master practice with the same test Lichess's own "Learn from your mistakes" uses: the played move has at most one game in the Masters explorer. Show the main moves there with statistics. "Left theory" is never called a mistake unless the stored analysis also judged the move.
- **No LLM.** This matches the earlier decision that Chess Tweek never calls an AI. The measured error rates are too high for a coach that must never claim an unproven reason.

## Apps and tools

### Chess.com Game Review and Coach

The official classification article defines categories by **expected points lost** ([chess.com support](https://support.chess.com/en/articles/8572705-how-are-moves-classified-what-is-a-blunder-or-brilliant-etc)):

| Class | Expected points lost |
|---|---|
| Best | 0.00 |
| Excellent | 0.00–0.02 |
| Good | 0.02–0.05 |
| Inaccuracy | 0.05–0.10 |
| Mistake | 0.10–0.20 |
| Blunder | 0.20–1.00 |

Brilliant, Great, and Miss use separate rules, as described in [the same article](https://support.chess.com/en/articles/8572705-how-are-moves-classified-what-is-a-blunder-or-brilliant-etc):
- **Brilliant** is a good piece sacrifice that is best or nearly best.
- **Great** is a move "critical to the outcome", such as the only good move.
- **Miss** is failing to take advantage of an opponent's mistake.

Expected points depend on **the player's rating** as well as the engine evaluation, and new players get more generous Brilliant and Great classifications. The glossary defines **Book** as "a move that's part of opening theory" ([chess.com terms](https://www.chess.com/terms/game-review)). The reference database behind Book is not published (unverified).

How the coach text is produced is **not public**. The 2022 launch article says explanations are based on computer evaluations and credits the people "who worked on the explanation engine", without describing it ([chess.com news, 2022-11-09](https://www.chess.com/news/view/chesscom-releases-new-game-review)). Treat any claim about templates versus models as unverified.

Lessons for Chess Tweek: chess.com's *Miss* is close to Chess Tweek's `missed` kind. Its "only good move" idea for Great can be measured from MultiPV gaps. Rating-dependent thresholds would be an owner decision, and are not recommended here.

### Lichess analysis, accuracy, and "Learn from your mistakes"

- **Win chance.** Lichess converts centipawns to winning chances in [−1, +1] with `2 / (1 + exp(−0.00368208 × cp)) − 1`, and to Win% as `50 + 50 ×` that ([scalachess `eval.scala`](https://github.com/lichess-org/scalachess/blob/176d2f70958dd1caa3d9f5bc3bade71b7ebfd311/core/src/main/scala/eval.scala#L102-L110)).
- **Judgements.** A drop in winning chances of at least 0.1, 0.2, or 0.3 is an Inaccuracy, Mistake, or Blunder; that is 5, 10, and 15 Win% points. Mate transitions get their own text, such as "Checkmate is now unavoidable" or "Lost forced checkmate sequence". The only verbal comment is the judgement plus "X was best" ([lila `Advice.scala`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/modules/tree/src/main/Advice.scala#L45-L58)).
- **Accuracy.** Per-move accuracy is an exponential fit on Win% lost. Game accuracy averages a volatility-weighted mean and a harmonic mean ([lila `AccuracyPercent.scala`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/modules/analyse/src/main/AccuracyPercent.scala)).
- **Learn from your mistakes.** This picks the moves where winning chances swung by more than 0.1, or where a mate in three or fewer was lost ([`nodeFinder.ts`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/analyse/src/nodeFinder.ts#L19-L30)). Before the middlegame starts, it queries the **Masters opening explorer**. It drops the mistake if the played move occurs in more than one master game, and accepts any master move as a correct answer. Any other answer is correct if it loses less than 0.04 winning chances ([`retroCtrl.ts`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/analyse/src/retrospect/retroCtrl.ts#L102-L125), [same file](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/analyse/src/retrospect/retroCtrl.ts#L160-L170)). This is the clearest public "left theory" rule found.

### En Croissant

En Croissant annotates with symbols only, in the code read for this investigation. A Win% drop over 20, 10, or 5 points gives `??`, `?`, or `?!`. A best move that beats the second line by more than 10 points becomes `!`, or `!!` when it is a sacrifice ([`score.ts`](https://github.com/franciscoBSalgueiro/en-croissant/blob/c8473936129bb6f1a919882e45527daa4d3a1e34/src/utils/score.ts#L75-L117)). No natural-language reasons were found.

### ChessBase / Fritz

Fritz "Full analysis / Tactical Analysis" documents what it outputs, but not its selection rules ([Fritz 19 help](https://help.chessbase.com/Fritz/19/Eng/000017.htm)):
- verbal comments on opening theory, tactical motifs, mistakes, and turning points
- threats, refutations of "weak moves which were not played", and training questions at critical moments

### DecodeChess and Aimchess

**DecodeChess** describes itself as explainable AI on top of Stockfish NNUE. It reports threats, plans, and "the function of each meaningful piece", but does not publish the method ([decodechess.com](https://decodechess.com/)).

**Aimchess** works across many games, not one move. It rates six areas against same-rated players ([aimchess.com](https://aimchess.com)):
- Tactics
- Endgame
- Advantage capitalization
- Resourcefulness
- Time management
- Opening performance

Its axes are a useful model for summarising error categories over many games.

## Tactic theme detection (lichess-puzzler)

The tagger ([`tagger/cook.py`](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py), AGPL-3.0) receives a puzzle line. The line starts with the opponent's mistake, followed by the solver's moves at odd positions. Every test runs over that line.

**The precondition matters most.** The generator accepts a line only if, at each solver move, the best move's winning chance beats the second-best by more than 0.7 on the [−1, +1] scale, or the move is the only good mate ([`generator.py`](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/generator/generator.py#L55-L61)). So the tagger can assume the line is forcing and that its gains are real. Chess Tweek's stored best line has no such guarantee, which is why its detector must also prove the gain.

Shared helpers ([`tagger/util.py`](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/util.py#L72-L123)):
- A piece is *defended* if a friendly piece attacks its square, including a defender behind an attacking slider (x-ray defence).
- A piece is *in a bad spot* if it is attacked and either undefended or attackable by a cheaper piece.
- A piece is *trapped* if it is in a bad spot, is not pinned, the side to move is not in check, and every legal move of it either lands in a bad spot or captures something worth less.

Each theme, in our own words:

| Theme | Detection logic | Source |
|---|---|---|
| Fork | A solver move lands safely on a square from which it attacks at least two non-pawn enemy pieces. Each target must be worth more than the attacker, or be undefended and unable to hit back. | [L217–238](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L217-L238) |
| Hanging piece | The first solver move captures an undefended non-pawn piece. It is excluded if this only recaptures equal material, and it must not lose material over the next two moves. | [L241–265](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L241-L265) |
| Pin | Either a pinned enemy piece cannot capture a solver piece off its pin line, or a solver piece attacks a pinned piece along the pin line and that piece cannot escape. In the first case the target must be worth more or be undefended. In the second, the pinned piece must be worth more than its attacker, or be undefended with a move off the line. | [L576–631](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L576-L631) |
| Skewer | A solver slider captures a piece that was behind an enemy piece. That front piece just moved off the line, was worth more than the captured piece, and the capture square was in a bad spot. | [L470–492](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L470-L492) |
| Discovered attack / check | A check is given by a piece other than the one that moved. Or a solver capture travels along a line that the solver's previous move vacated. | [L286–313](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L286-L313) |
| Deflection | The solver captures or promotes on a square that an enemy piece guarded until the previous move. That piece left because it recaptured on the solver's earlier square or answered a check, and it no longer guards the target. | [L397–440](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L397-L440) |
| Attraction | The solver moves to a square, an enemy king, queen, or rook captures there, and the solver then attacks that square. It must then give check if the attracted piece was the king, or capture there two moves later. | [L368–394](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L368-L394) |
| Interference | The solver captures an undefended piece that a sliding defender protected two moves earlier. Either the solver's earlier move or the opponent's own move landed between that defender and the target. | [L495–549](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L495-L549) |
| Intermezzo | The opponent captures, the solver plays something else instead of the obvious recapture, and the solver recaptures later. The recapture must already have been legal right after the opponent's capture. | [L552–572](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L552-L572) |
| Trapped piece | A solver capture takes a piece that was trapped (helper above) in the position two moves earlier. | [L268–279](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L268-L279) |
| X-ray | Solver and opponent trade on the same square. The solver's final capture comes from behind, through the square the opponent's piece left. | [L194–214](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L194-L214) |
| Clearance | A solver slider moves without capturing onto or through a square that the solver's own previous move vacated. That previous move was a free or bad-spot move, and neither move was a check response. | [L685–717](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L685-L717) |
| Capturing defender | The solver captures, with a piece worth at least as much, an undefended piece that defended a square. On the next solver move, the solver wins on that square or mates. | [L786–816](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L786-L816) |
| Overloading | **Not detected**: the function always returns false. | [L282–283](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L282-L283) |
| Sacrifice | Material drops by two or more after a later solver move, without a promotion. | [L184–191](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py#L184-L191) |

Two lessons follow:
1. Every test is local: a two-to-four-ply pattern plus attack geometry. That fits the stored engine lines well.
2. Lichess itself gave up on overloading. That is a signal to postpone it in Chess Tweek too.

## Explainability research and LLM commentary

- **Engines do not hold human reasons.** Stockfish 16.1 "marks the removal of the traditional handcrafted evaluation" ([Stockfish blog](https://stockfishchess.org/blog/2024/stockfish-16-1/)). The earlier hand-written evaluation scored named terms such as material, imbalance, mobility, threats, passed pawns, and space ([Stockfish 15 `evaluate.cpp`](https://github.com/official-stockfish/Stockfish/blob/sf_15/src/evaluate.cpp)), and those terms no longer exist. This supports the plan's wording rule: positional features are observations, not the engine's reasons.
- **Concept probing.** Probes show that AlphaZero's network learns many human concepts during training ([McGrath et al., "Acquisition of Chess Knowledge in AlphaZero"](https://arxiv.org/abs/2111.09259), PNAS 2022). AlphaZero-derived concepts can even be taught to grandmasters ([Schut et al., 2023](https://arxiv.org/abs/2310.16410)). These are research methods on one network, not tools for explaining one owner's move.
- **Commentary generation.** One study trained on 298K move–comment pairs from forum data, and its outputs were judged close to human comments on *direct move descriptions* ([Jhamtani et al., ACL 2018](https://aclanthology.org/P18-1154/)). Later work built an engine into the generator ([Zang et al., ACL 2019](https://aclanthology.org/P19-1597/)) or combined symbolic engines with language models ([Lee et al., 2022](https://arxiv.org/abs/2212.08195)).
- **LLM reliability.** LLMs "are prone to hallucinations" in chess commentary; one proposed fix feeds them engine-derived concepts ([Kim et al., NAACL 2025](https://arxiv.org/abs/2410.20811)). A 2026 benchmark checks each atomic claim against engine and board tools. It found that "factual hallucinations remain pervasive": GPT-5.4 without tools got 22.0% of sub-claims wrong, smaller open-weight models over 40%. Tools helped, but coverage of expert ideas stayed limited ([Hebbar et al., 2026](https://arxiv.org/abs/2608.04240)).
- **Human-like engines.** Maia predicts human moves by rating band and showed that large human mistakes can be predicted ([McIlroy-Young et al., KDD 2020](https://arxiv.org/abs/2006.01855)). It is interesting for "would a 1500 find this?", but it needs new model files and runtime, so it is a dependency decision. It is not recommended now.

## Opening context

- **The opening-name dataset.** [lichess-org/chess-openings](https://github.com/lichess-org/chess-openings/blob/48a8c0e733facfdc9a3723fe882e51ec28014074/README.md) is CC0. It has five TSV files (A–E, matching ECO volumes) with `eco`, `name`, and `pgn`; the build adds `uci` and `epd`.
  - The README recommends classifying a game by playing "moves backwards until a named position is found". It adds entries for common transpositions, so lookup by position (EPD) also finds named positions reached by another move order.
  - The snapshot read for this investigation has 3,865 entries, 22 of them with "London System" in the name and 111 with "Caro-Kann". Measured from the TSV files.
- **The explorer API** ([lichess-org/api specs](https://github.com/lichess-org/api/tree/22570e561de82d93214b62bc5beb56cef14533a6/doc/specs/tags/openingexplorer)) has three databases, now served from `explorer.lichess.org` and declared with OAuth2 security:
  - `/masters` for over-the-board master games
  - `/lichess`, filterable by `speeds` and by rating group: 0, 1000, 1200 and so on up to 2500
  - `/player`, which streams results while it indexes a player's games
  
  Each response gives White, draw, and Black counts per move, average rating, and the opening name when the position is named.
- **Detecting "out of book".**
  - Lichess "Learn from your mistakes" uses at most one Masters game for the played move, applied before the middlegame starts (see above).
  - Chess.com's Book database is not public.
  - Fritz says it considers opening theory but does not document the rule ([Fritz help](https://help.chessbase.com/Fritz/19/Eng/000017.htm)).
  - "Last named position" is a different signal from "left master practice". Many named lines end after two to four moves, while master practice continues much longer.
- **Opening plans are authored, not generated.**
  - Lichess's opening pages and analysis board embed Wikibooks *Chess Opening Theory* text ([lila `wikiBooks.ts`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/lib/src/wikiBooks.ts)). Wikibooks text is CC BY-SA, not CC0 (licence per Wikibooks site terms; not re-checked here), so shipping it would need attribution and share-alike.
  - DecodeChess claims generated "plans", but its method is not public.

## Coach and grandmaster methods

- **Kotov, *Think Like a Grandmaster* (Batsford, 1971).** Identify the candidate moves first, then analyse each branch of the "tree of analysis" once ([Wikipedia, candidate move](https://en.wikipedia.org/wiki/Candidate_move); secondary). This argues for showing the owner the engine's other candidates (MultiPV lines 2 and 3), not only the best move.
- **Dvoretsky and Yusupov, *Training for the Tournament Player*, chapter 2 "Analyze Your Own Games".** Yusupov: "analysis of one's own games is the main method by which a chessplayer can improve." Steps: find the turning points, find the *reasons* for the mistakes, look for missed possibilities, and check the opening against theory. Quoted in a [chess.com blog summary](https://chess.com/blog/Chessmo/analyze-your-own-games); the book wording was not checked directly.
- **Botvinnik** treated analysing his own games, and publishing the analysis for criticism, as a central training method ([GM Gserper, chess.com, 2024](https://www.chess.com/article/view/how-to-analyze-your-own-chess-games); secondary).
- **Analyse before the engine.** "Engines should therefore be used after your own analysis, not before". Write down thoughts and variations, focus on large evaluation swings, and finish with two to four takeaways. The areas to check include tactics and calculation, positional ideas, recurring mistakes, time trouble, and where opening knowledge ran out ([GM Roeland Pruijssers, chess.com, 2026](https://www.chess.com/blog/GMRoelandPruijssers/how-to-analyze-your-own-chess-games-correctly)). The same advice appears in [Gserper](https://www.chess.com/article/view/how-to-analyze-your-own-chess-games) and in `04-training-goals.md` §6.3.
- **Heisman's "Hope Chess" and "Real Chess".** Hope chess is "consistently making moves without analyzing the opponent's possible forcing replies". Real chess means you "consistently analyze to see if each candidate move is safe", and tactical vision alone is "only part of" that safety check ([Dan Heisman, chess.com](https://www.chess.com/article/view/passive-vs-basic-hope-chess)). Most club-level allowed tactics fit this cause, but only the player knows whether the check was skipped.
- **Aagaard's positional questions.** "Where are the weaknesses? What is the worst placed piece? What is your opponents idea?" (*Grandmaster Preparation: Positional Play*, Quality Chess; wording as quoted in [John Donaldson's review](https://www.jeremysilman.com/book-review/positional-play/)). These are good prompts when no tactic explains a loss.
- **De la Villa, *100 Endgames You Must Know* (New in Chess).** A finite set of frequent endgames chosen to be easy to learn and useful in harder positions ([publisher listing](https://www.simonandschuster.com/books/100-Endgames-You-Must-Know/Jesus-de-la-Villa/9789083311210)). This suits the training suggestion for endgame-phase mistakes.
- **Error categories in practice** combine the type of mistake with its human cause:
  - tactical oversight (Heisman's safety check)
  - calculation error
  - evaluation or positional misjudgement (Aagaard's questions)
  - time trouble
  - opening knowledge
  
  Aimchess shows the same idea aggregated over many games.

## Recommended method for Chess Tweek

One principle runs through every part: **every sentence on a coach card is backed by a named piece of stored evidence.** That evidence is a line, a measured feature, a clock value, or the owner's own note. Anything else is left out.

### Part 1 (Stage 8B, first): more named tactics

Each detector runs on two lines, the same way the current detector works:
- the stored best line before the move (`missed`)
- the opponent's reply line after the played move (`allowed`)

Each detector needs (a) the geometric pattern and (b) a material or mate gain that actually happens later in the same line, within a fixed window. This is the extra proof that lichess-puzzler gets for free from its single-solution lines. Each motif gets a test position and a near miss. Rules are written fresh in Java on the existing `chessgame` library and the `Attacks` helper, so no new dependency is needed.

| Order | Motif | Detection approach (proof in the stored line) |
|---|---|---|
| 1 | Discovered attack / discovered check | A gaining-side move uncovers a ray from its own slider to an enemy king or to a piece worth more than the slider, or an undefended one. The gain is a capture on that ray, or the uncovered check, within the window. |
| 2 | Pin | After a gaining-side move, an enemy piece stands on a ray between the gaining slider and the enemy king or a more valuable piece. The gain is the pinned piece being captured, or a capture the pinned piece could otherwise have answered. The current `Attacks` helper ignores pins, so this needs a ray-based pin test. |
| 3 | Skewer | A slider attacks a more valuable piece (or gives check). That piece moves off the ray, and the slider captures the piece behind it within two plies. |
| 4 | Removing the defender | The gaining side captures piece D, which was the only defender of square S. Within the next two gaining-side moves it captures on S or mates there. |
| 5 | Trapped piece | Use an in-house trapped-piece test on the position: every legal move of the piece leaves it attacked and losing material. The gain is its capture in the line. |
| 6 | Deflection | A forcing gaining move (check, or capture of something worth at least the defender) makes a defender leave a duty square. The gain is a capture or mate on that square. |
| 7 | In-between move | In a capture sequence, the side expected to recapture first plays a check or bigger threat, recaptures later, and nets more material than the direct recapture would have. |

Overloading, interference, clearance, attraction, and x-ray come later, if ever. Lichess does not detect overloading at all, and the others are rare at this level.

Add a separate **"only move"** signal from data already stored: MultiPV line 1 and line 2 differ by at least about 20 percentage points of expected score. The value is a starting proposal, to be tested on stored games. Wording: "This was the only move that kept the advantage; the next best, X, scored N points less." It is honest because both lines were searched.

### Part 2 (Stage 8B, second): measurable positional observations

Compare **matched positions**, not the raw position before the move:
- the position at the end of the best line
- the position at the end of the played move's reply line

Both are taken at the same short horizon, around four to six plies, and after any pending capture sequence ends, so that half-finished trades do not show as material changes. Report only features whose difference passes a fixed threshold.

| Reliability | Feature | Definition |
|---|---|---|
| High | Material balance | Pawn 1, knight and bishop 3, rook 5, queen 9. |
| High | Bishop pair | Kept or lost. |
| High | Passed pawns | No enemy pawn ahead on the same or adjacent files. |
| High | Isolated pawns | No friendly pawn on adjacent files. |
| High | Doubled pawns | Two or more friendly pawns on one file. |
| High | King shelter | Count of own pawns on the king's file and the adjacent files, within two ranks in front of the king. |
| High | Open and half-open files next to the castled king | Files with no pawns, or none of one side's. |
| High | Castling | Castling rights lost, or king moved without castling. |
| High | Development (opening phase only) | Minor pieces still on their starting squares. |
| Medium | Mobility | Legal-move count. It swings with tactics, so show it only for large differences. |
| Low (postpone) | Backward pawns, space, outposts, "bad bishop" | Definitions vary and depend on context. |

Wording template: *"Compared with the engine's line, after your move: your king has one fewer pawn in front of it; your opponent gets a passed pawn on d5. These are observations; the engine does not say they are the reason."* When nothing changes: *"No tactic or measured feature explains the difference. The engine's line keeps a better position for reasons this review cannot name."* Then add Aagaard's three questions as prompts for the owner, not as answers.

### Part 3: explanation wording rules

1. Give the evidence before the claim, and keep the claim no larger than the evidence. Use "The engine's line wins the knight after 14.Bb5, pinning it to the king", not "you missed a pin".
2. Distinguish **missed** (your chance) from **allowed** (their chance), the way chess.com's Miss does.
3. Use three confidence tiers in text:
   - *proven*: a tactic or mate from the line
   - *observed*: a feature change
   - *unknown*: say so plainly
4. Never use causal words such as "because" or "the idea is" for positional observations, and never guess the owner's reason.
5. Offer a training link for proven motifs: Lichess theme names `pin`, `skewer`, `discoveredAttack`, `deflection`, `capturingDefender`, `trappedPiece`, `intermezzo`, which map directly onto the imported corpus.

### Part 4: thought-process capture (owner input)

Every coaching source above says the owner's own pre-engine view is what reveals *why*. The owner dropped the free-text "what I was thinking" note on 2026-11-15 (`docs/dev/2026-11-15-tactic-labels-coach-cards.md`). A lighter version is recommended:

- an optional one-tap **cause** on each mistake, chosen before or after viewing the engine line:
  - Didn't see their reply (safety check)
  - Didn't consider the better move
  - Calculated it wrong
  - Misjudged the position
  - Short on time
  - Didn't know the opening
  - Not sure
- optionally, "try first": find a better move before the best move is revealed. Own Puzzles in Stage 8 already supply the repeat practice.

This needs an owner decision because it reverses the earlier one.

### Part 5: error categories (two axes)

| Axis | Values | Source of truth |
|---|---|---|
| Type (automatic) | missed tactic (motif), allowed tactic (motif), mate missed or allowed, material without a named motif, positional (observations), opening deviation, endgame (by phase) | stored lines, features, phase |
| Cause (owner or clock) | threat not seen, move not considered, calculation, evaluation, time pressure, opening knowledge, unknown | owner's tap; *time pressure* also from stored clocks when available (for example, under 10% of the initial time or under 60 s; threshold to decide) |

Aggregated over games, the two axes give Aimchess-style summaries, such as "most of your blunders are allowed tactics when short on time". These summaries are computed only from labelled data.

### Part 6 (Stage 10): opening context

1. **Name every position.** Load the CC0 TSV files once and index them by EPD, using the dataset's own convention of position lookup to handle transpositions. For each ply, show the deepest named position reached so far. This is offline and needs no network.
2. **Left master practice.** Walk the game from move 1 and query the Masters explorer for each position, through the server with caching. The deviation point is the first own move with at most one Masters game, the same rule Lichess uses. Stop after the opening phase, or after about 15 moves, to bound the number of requests.
3. **Left practice at my level** (optional). Use the same test against `/lichess` filtered to `rapid` and the owner's rating groups, for example 1400–2000. This answers "what do opponents actually play".
4. **Show** the main moves at the deviation point with their counts and score percentages. Show whether the stored analysis judged the deviation: "You left master practice with 6...Qb6; the engine judged it fine (−1 point)" versus "...and it was a mistake". Leaving theory is never labelled an error by itself.
5. **Plans** come only from authored text: the owner's notes, plus optional project-written starter notes for the London System and the Caro-Kann Defense, which is already an open plan decision. Generated plans are not offered.

### Why not an LLM

- The project rule is that Chess Tweek "never calls an AI" (`04-training-goals.md` §8).
- The best measured model still states about one wrong sub-claim in five without tool checks ([Hebbar et al., 2026](https://arxiv.org/abs/2608.04240)).
- A cloud model breaks local-first; a local model is a large new dependency and measured worse.

If this is ever reconsidered, the only safe role is rewording facts the deterministic layer already proved, with every output claim re-verified. That is a separate owner decision.

## Open owner decisions

1. **Thought-process capture.** Reinstate an optional one-tap cause (and optional "try first") on mistakes, reversing the 2026-11-15 drop of the thinking note?
2. **"Left theory" rule.** Accept the Lichess-compatible threshold (at most one Masters game). Also add the rating-band `/lichess` check, and choose which rating groups and speeds?
3. **Explorer calls during review.** Allow Game Review to call the Lichess explorer with the owner's token, server-side, cached, and with failure leaving the review usable? Or show opening context only after an explicit owner action?
4. **Time-pressure threshold** for the cause axis, and confirm that clocks are stored for chess.com games as well as Lichess games before relying on them.
5. **"Only move" threshold** (proposed starting point about 20 expected-score points between MultiPV lines 1 and 2), to be tuned on stored games. Decided 2026-11-24: included in Stage 8B Part 2 at 20 points.
6. **Scope of positional observations.** Mistakes and blunders only, or inaccuracies too? Decided 2026-11-24: inaccuracies too.
7. **Opening plan text.** Starter notes written for the project versus owner notes only (already open in Stage 10). Shipping Wikibooks text would need CC BY-SA attribution and share-alike, which is not recommended.
8. **LLM.** Confirm "no LLM" for Stage 8B and Stage 10. Any later LLM or Maia use would be a new dependency proposal.

No new library dependency is required for the recommended method. Bundling the CC0 opening TSV files is new *data*, not code; owner confirmation is still advisable.

## Sources

- Chess.com: [move classification](https://support.chess.com/en/articles/8572705-how-are-moves-classified-what-is-a-blunder-or-brilliant-etc), [Game Review glossary](https://www.chess.com/terms/game-review), [Game Review launch](https://www.chess.com/news/view/chesscom-releases-new-game-review)
- Lichess code: [`Advice.scala`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/modules/tree/src/main/Advice.scala), [`AccuracyPercent.scala`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/modules/analyse/src/main/AccuracyPercent.scala), [scalachess `eval.scala`](https://github.com/lichess-org/scalachess/blob/176d2f70958dd1caa3d9f5bc3bade71b7ebfd311/core/src/main/scala/eval.scala), [`retroCtrl.ts`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/analyse/src/retrospect/retroCtrl.ts), [`nodeFinder.ts`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/analyse/src/nodeFinder.ts), [`wikiBooks.ts`](https://github.com/lichess-org/lila/blob/1f25f6ae91088627103f1e3b6da76fa78e78ff8d/ui/lib/src/wikiBooks.ts)
- lichess-puzzler (AGPL-3.0): [`tagger/cook.py`](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/cook.py), [`tagger/util.py`](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/tagger/util.py), [`generator/generator.py`](https://github.com/ornicar/lichess-puzzler/blob/8e24b6d5e2b9618e41581a327926e4ac67c610a2/generator/generator.py)
- Openings: [lichess-org/chess-openings](https://github.com/lichess-org/chess-openings/tree/48a8c0e733facfdc9a3723fe882e51ec28014074) (CC0), [Opening Explorer API specs](https://github.com/lichess-org/api/tree/22570e561de82d93214b62bc5beb56cef14533a6/doc/specs/tags/openingexplorer)
- Other tools: [En Croissant `score.ts`](https://github.com/franciscoBSalgueiro/en-croissant/blob/c8473936129bb6f1a919882e45527daa4d3a1e34/src/utils/score.ts) (GPL-3.0), [Fritz 19 Full analysis](https://help.chessbase.com/Fritz/19/Eng/000017.htm), [DecodeChess](https://decodechess.com/), [Aimchess](https://aimchess.com)
- Engines and research: [Stockfish 16.1](https://stockfishchess.org/blog/2024/stockfish-16-1/), [Stockfish 15 `evaluate.cpp`](https://github.com/official-stockfish/Stockfish/blob/sf_15/src/evaluate.cpp), [McGrath et al.](https://arxiv.org/abs/2111.09259), [Schut et al.](https://arxiv.org/abs/2310.16410), [Jhamtani et al.](https://aclanthology.org/P18-1154/), [Zang et al.](https://aclanthology.org/P19-1597/), [Lee et al.](https://arxiv.org/abs/2212.08195), [Kim et al.](https://arxiv.org/abs/2410.20811), [Hebbar et al.](https://arxiv.org/abs/2608.04240), [McIlroy-Young et al.](https://arxiv.org/abs/2006.01855)
- Coaching: [Heisman, Passive vs Basic Hope Chess](https://www.chess.com/article/view/passive-vs-basic-hope-chess), [Pruijssers](https://www.chess.com/blog/GMRoelandPruijssers/how-to-analyze-your-own-chess-games-correctly), [Gserper](https://www.chess.com/article/view/how-to-analyze-your-own-chess-games), [Dvoretsky/Yusupov summary](https://chess.com/blog/Chessmo/analyze-your-own-games), [Donaldson on Aagaard](https://www.jeremysilman.com/book-review/positional-play/), [Candidate move](https://en.wikipedia.org/wiki/Candidate_move), [De la Villa listing](https://www.simonandschuster.com/books/100-Endgames-You-Must-Know/Jesus-de-la-Villa/9789083311210)
