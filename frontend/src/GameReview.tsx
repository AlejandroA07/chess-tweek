import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react'
import styles from './GameReview.module.css'
import { ChessBoard, type BoardOrientation } from './ChessBoard'
import { playUci } from './boardPosition'
import { coachCard, tacticLabel, type CoachCard } from './coach'
import { GameAnalysisPanel } from './GameAnalysisPanel'
import { jobGame, type AnalysisPerspective } from './analysis'
import { gameKey, type GameRef } from './gameRef'
import {
  ReviewRequestError,
  requestReview,
  type EngineLine,
  type GameReview as Review,
  type JudgedMove,
  type Phase,
  type PhaseStatistics,
  type ReviewFailure,
  type ReviewSide,
  type Severity,
} from './reviewData'
import { openingLabel } from './gameCatalog'
import { useGameAnalysis } from './useGameAnalysis'

type LoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; review: Review }
  | { kind: 'failed'; failure: Exclude<ReviewFailure, 'not_connected'> }

const SEVERITY_TEXT: Record<Exclude<Severity, 'none'>, string> = {
  inaccuracy: 'Inaccuracy',
  mistake: 'Mistake',
  blunder: 'Blunder',
}
const SEVERITY_SYMBOL: Record<Exclude<Severity, 'none'>, string> = {
  inaccuracy: '?!',
  mistake: '?',
  blunder: '??',
}
const PHASE_TEXT: Record<Phase, string> = {
  opening: 'Opening',
  middlegame: 'Middlegame',
  endgame: 'Endgame',
}
const SIDE_TEXT: Record<ReviewSide, string> = {
  my_moves: 'My Moves',
  opponent_moves: 'Opponent Moves',
}

/**
 * One stored game: board, moves, evaluation progression, and one analyzed side's findings.
 * {@code initialPly}, from an Own Puzzle's source link, opens My Moves at that move with its
 * finding in view.
 */
export function GameReview({
  game,
  initialPly = null,
  onSessionFailure,
}: {
  game: GameRef
  initialPly?: number | null
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const { source, id } = game
  const [side, setSide] = useState<ReviewSide | null>(initialPly === null ? null : 'my_moves')
  // Applied once, when the first review arrives; later reloads keep the owner's position.
  const pendingPly = useRef<number | null>(initialPly)
  const [state, setState] = useState<LoadState>({ kind: 'loading' })
  const [reloadTick, setReloadTick] = useState(0)
  const [index, setIndex] = useState(0)
  const [flipped, setFlipped] = useState(false)
  const [focusedPly, setFocusedPly] = useState<number | null>(null)
  const boardPanel = useRef<HTMLDivElement | null>(null)
  const lastPerspective = useRef<AnalysisPerspective>('my_moves')

  const reload = useCallback(() => setReloadTick((tick) => tick + 1), [])
  const analysis = useGameAnalysis({ enabled: true, onCompleted: reload, onSessionFailure })

  useEffect(() => {
    const controller = new AbortController()
    setState((current) => current.kind === 'ready' ? current : { kind: 'loading' })
    requestReview({ source, id }, side, controller.signal)
      .then((review) => {
        setState({ kind: 'ready', review })
        const ply = pendingPly.current
        pendingPly.current = null
        if (ply !== null && ply <= review.plies.length) {
          // As when a finding is chosen: the position after the move, with the move marked.
          const finding = review.selected?.moves.some((move) => move.ply === ply && move.label !== 'none')
          setIndex(ply)
          setFocusedPly(finding ? ply : null)
        } else {
          setIndex((current) => Math.min(current, review.plies.length))
        }
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        const failure = error instanceof ReviewRequestError ? error.failure : 'review_unavailable'
        if (failure === 'not_connected') {
          onSessionFailure('not_connected')
          return
        }
        setState({ kind: 'failed', failure })
      })
    return () => controller.abort()
  }, [source, id, side, reloadTick, onSessionFailure])

  if (state.kind === 'loading') {
    return (
      <section className={styles.notice} aria-live="polite" aria-busy="true">
        <BackLink />
        <h2>Loading game review…</h2>
      </section>
    )
  }

  if (state.kind === 'failed') {
    return <ReviewFailureNotice failure={state.failure} onRetry={reload} />
  }

  const { review } = state
  return (
    <ReviewWorkspace
      review={review}
      index={index}
      onIndexChange={(next) => {
        setIndex(next)
        setFocusedPly(null)
      }}
      flipped={flipped}
      onFlip={() => setFlipped((value) => !value)}
      focusedPly={focusedPly}
      onViewFinding={(move) => {
        // The board shows the position after the mistake, with the played move marked.
        setIndex(move.ply)
        setFocusedPly(move.ply)
      }}
      onSelectSide={(next) => {
        setSide(next)
        setFocusedPly(null)
      }}
      boardPanel={boardPanel}
      analysisPanel={(
        <GameAnalysisPanel
          job={analysis.job === null || gameKey(jobGame(analysis.job)) === gameKey(review.game) ? analysis.job : null}
          opponentName={review.game.opponent.name}
          starting={analysis.starting}
          canceling={analysis.canceling}
          startFailure={analysis.startFailure}
          now={Date.now()}
          onCancel={analysis.cancel}
          onRetry={() => analysis.start(review.game, lastPerspective.current)}
          onDismiss={analysis.dismiss}
        />
      )}
      analysisLocked={analysis.starting || analysis.job?.state === 'running'}
      onAnalyze={(perspective) => {
        lastPerspective.current = perspective
        if (perspective !== 'both') setSide(perspective)
        analysis.start(review.game, perspective)
      }}
    />
  )
}

function BackLink() {
  return <a className={styles.backLink} href="#/games">Back to Games</a>
}

function ReviewFailureNotice({
  failure,
  onRetry,
}: {
  failure: Exclude<ReviewFailure, 'not_connected'>
  onRetry: () => void
}) {
  const messages: Record<typeof failure, { title: string; text: string; retry: boolean }> = {
    game_not_found: {
      title: 'This game is not stored',
      text: 'It may have been removed, or the link is wrong. Return to Games to choose another game.',
      retry: false,
    },
    session_unavailable: {
      title: 'The Lichess session could not be checked',
      text: 'Your local data is safe. Check your connection and try again.',
      retry: true,
    },
    review_unavailable: {
      title: 'This review could not be loaded',
      text: 'The stored analysis could not be read. Try again.',
      retry: true,
    },
  }
  const message = messages[failure]
  return (
    <section className={styles.notice} role="alert">
      <BackLink />
      <h2>{message.title}</h2>
      <p>{message.text}</p>
      {message.retry && (
        <button className={styles.secondaryButton} type="button" onClick={onRetry}>Retry</button>
      )}
    </section>
  )
}

/** An engine line being stepped through on the board instead of the game. */
type LineView = { ply: number; source: 'best' | 'reply'; step: number }

type ResolvedLine = {
  view: LineView
  finding: JudgedMove
  line: EngineLine
  firstPly: number
  moves: string[]
  fens: string[]
}

/**
 * The engine line of a finding as playable positions: the best line from the position before the
 * mistake, or the opponent's best reply line from the position after it. Only the moves the server
 * could replay ({@code pvSan}) are used.
 */
function resolveLine(view: LineView, finding: JudgedMove, plies: Review['plies']): ResolvedLine | null {
  const line = view.source === 'best' ? finding.before[0] : finding.after[0]
  if (!line || line.pvSan.length === 0) return null
  const start = view.source === 'best' ? finding.fenBefore : plies[finding.ply - 1]?.fen
  if (!start) return null
  const fens = [start]
  const moves: string[] = []
  for (const uci of line.pv.slice(0, line.pvSan.length)) {
    const next = playUci(fens[fens.length - 1], uci)
    if (next === null) break
    fens.push(next)
    moves.push(uci)
  }
  return {
    view: { ...view, step: Math.max(0, Math.min(moves.length, view.step)) },
    finding,
    line,
    firstPly: view.source === 'best' ? finding.ply : finding.ply + 1,
    moves,
    fens,
  }
}

function ReviewWorkspace({
  review,
  index,
  onIndexChange,
  flipped,
  onFlip,
  focusedPly,
  onViewFinding,
  onSelectSide,
  boardPanel,
  analysisPanel,
  analysisLocked,
  onAnalyze,
}: {
  review: Review
  index: number
  onIndexChange: (index: number) => void
  flipped: boolean
  onFlip: () => void
  focusedPly: number | null
  onViewFinding: (move: JudgedMove) => void
  onSelectSide: (side: ReviewSide) => void
  boardPanel: RefObject<HTMLDivElement | null>
  analysisPanel: ReactNode
  analysisLocked: boolean
  onAnalyze: (perspective: AnalysisPerspective) => void
}) {
  const [lineView, setLineView] = useState<LineView | null>(null)
  const { game, plies, selected } = review
  const baseOrientation: BoardOrientation = selected?.analyzedColor ?? game.ownerColor
  const orientation: BoardOrientation = flipped
    ? (baseOrientation === 'white' ? 'black' : 'white')
    : baseOrientation
  const judgedByPly = useMemo(
    () => new Map((selected?.moves ?? []).map((move) => [move.ply, move])),
    [selected],
  )
  const findings = (selected?.moves ?? []).filter((move) => move.label !== 'none')
  const focused = focusedPly === null ? null : judgedByPly.get(focusedPly) ?? null
  const lineFinding = lineView === null ? null : judgedByPly.get(lineView.ply) ?? null
  const line = lineView !== null && lineFinding !== null ? resolveLine(lineView, lineFinding, plies) : null
  const showingFinding = line === null && focused !== null && index === focused.ply

  const lastPly = index === 0 ? null : plies[index - 1]
  let fen = index === 0 ? review.initialFen : plies[index - 1].fen
  let highlight: string | null = lastPly?.uci ?? null
  let arrow: string | null = null
  let positionText = lastPly === null ? 'Starting position.' : `Position after ${moveLabel(lastPly.ply, lastPly.san)}.`
  if (line !== null) {
    const { step } = line.view
    fen = line.fens[step]
    highlight = step === 0 ? null : line.moves[step - 1]
    arrow = step === 0 ? line.moves[0] : null
    positionText = step === 0
      ? `Engine line, not played in the game: starts from the position ${line.view.source === 'best' ? 'before' : 'after'} ${moveLabel(line.finding.ply, line.finding.san)}.`
      : `Engine line, not played in the game: ${formatVariation(line.firstPly, line.line.pvSan.slice(0, step))}.`
  } else if (showingFinding && focused) {
    positionText = `Position after your ${SEVERITY_TEXT[focused.label as Exclude<Severity, 'none'>].toLowerCase()} ${moveLabel(focused.ply, focused.san)}. Best was ${moveLabel(focused.ply, focused.bestMoveSan ?? focused.bestMove)}.`
  }

  const goTo = (next: number) => {
    setLineView(null)
    onIndexChange(Math.max(0, Math.min(plies.length, next)))
  }
  const stepTo = (next: number) => {
    if (line === null) return
    setLineView({ ...line.view, step: Math.max(0, Math.min(line.moves.length, next)) })
  }
  const openLine = (finding: JudgedMove, source: LineView['source'], step: number) => {
    onViewFinding(finding)
    setLineView({ ply: finding.ply, source, step })
    boardPanel.current?.focus()
  }
  const viewFinding = (finding: JudgedMove) => {
    setLineView(null)
    onViewFinding(finding)
    boardPanel.current?.focus()
  }

  const position = line === null ? index : line.view.step
  const last = line === null ? plies.length : line.moves.length
  const move = line === null ? goTo : stepTo
  const onBoardKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, () => void> = {
      ArrowLeft: () => move(position - 1),
      ArrowRight: () => move(position + 1),
      Home: () => move(0),
      End: () => move(last),
      Escape: () => setLineView(null),
    }
    const action = keys[event.key]
    if (action) {
      event.preventDefault()
      action()
    }
  }

  return (
    <div className={styles.review}>
      <ReviewHeader review={review} />
      {analysisPanel}

      {plies.length === 0 ? (
        <section className={styles.notice}>
          <h2>Moves are not available for this game</h2>
          <p>Only standard chess games can be replayed and analyzed. This game stays in your catalog.</p>
        </section>
      ) : (
        <div className={styles.workspace}>
          <section className={styles.boardColumn} aria-label="Board">
            {line !== null && (
              <div className={styles.lineBanner} role="status">
                <p>
                  <strong>{line.view.source === 'best' ? 'Best line' : 'After your move'}</strong>
                  {' · '}engine line, not played in the game
                </p>
                <LineMoves
                  firstPly={line.firstPly}
                  sans={line.line.pvSan.slice(0, line.moves.length)}
                  step={line.view.step}
                  onSelect={stepTo}
                  label={line.view.source === 'best' ? 'Best line moves' : 'Reply line moves'}
                />
                <button type="button" className={styles.secondaryButton} onClick={() => setLineView(null)}>
                  Back to the Game
                </button>
              </div>
            )}
            <div
              ref={boardPanel}
              className={styles.boardPanel}
              tabIndex={0}
              onKeyDown={onBoardKey}
              aria-label="Board navigation. Use the arrow keys, Home, and End to move through the game or the shown engine line."
            >
              <EvaluationBar
                review={review}
                index={index}
                orientation={orientation}
                override={line === null ? null : lineEvaluation(line)}
              />
              <ChessBoard
                fen={fen}
                orientation={orientation}
                label={`${positionText} ${orientation === 'white' ? 'White' : 'Black'} at the bottom.`}
                highlight={highlight}
                arrow={arrow}
              />
            </div>
            <p className={styles.positionText} aria-live="polite">{positionText}</p>
            {showingFinding && focused && (
              <div className={styles.findingCaption}>
                <p>
                  You played <span className={styles.notation}>{moveLabel(focused.ply, focused.san)}</span>
                  {' · '}{SEVERITY_TEXT[focused.label as Exclude<Severity, 'none'>]}
                  {' · '}{(focused.loss / 20).toFixed(1)} pts lost
                </p>
                <div className={styles.captionActions}>
                  <button type="button" className={styles.primaryButton} onClick={() => openLine(focused, 'best', 1)}>
                    Show Best Move: {moveLabel(focused.ply, focused.bestMoveSan ?? focused.bestMove)}
                  </button>
                  {focused.after[0] && focused.after[0].pvSan.length > 0 && (
                    <button type="button" className={styles.secondaryButton} onClick={() => openLine(focused, 'reply', 1)}>
                      Show Their Best Reply
                    </button>
                  )}
                </div>
                <CoachPanel
                  key={focused.ply}
                  card={coachCard(focused, plies, selected?.side === 'my_moves')}
                />
              </div>
            )}
            <div className={styles.boardControls} role="group" aria-label={line === null ? 'Move navigation' : 'Engine line navigation'}>
              <button type="button" onClick={() => move(0)} disabled={position === 0} aria-label={line === null ? 'First position' : 'Start of the line'}>|&lt;</button>
              <button type="button" onClick={() => move(position - 1)} disabled={position === 0} aria-label={line === null ? 'Previous move' : 'Previous line move'}>&lt;</button>
              <button type="button" onClick={() => move(position + 1)} disabled={position === last} aria-label={line === null ? 'Next move' : 'Next line move'}>&gt;</button>
              <button type="button" onClick={() => move(last)} disabled={position === last} aria-label={line === null ? 'Last position' : 'End of the line'}>&gt;|</button>
              <button type="button" className={styles.flipButton} onClick={onFlip}>Flip Board</button>
            </div>
            <p className={styles.orientation}>
              {orientation === 'white' ? 'White' : 'Black'} at the bottom
            </p>
          </section>

          <section className={styles.movesColumn} aria-label="Moves">
            <h2>Moves</h2>
            <MoveList
              plies={plies}
              index={line === null ? index : -1}
              judgedByPly={judgedByPly}
              phases={review.phases}
              onSelect={goTo}
            />
          </section>

          <section className={styles.analysisColumn} aria-label="Analysis">
            <SideTabs
              review={review}
              locked={analysisLocked}
              onSelect={(next) => {
                setLineView(null)
                onSelectSide(next)
              }}
              onAnalyze={onAnalyze}
            />
            {selected === null ? (
              <div className={styles.emptyAnalysis}>
                <h2>Not analyzed yet</h2>
                <p>Analyze this game with Stockfish to see statistics, every inaccuracy, mistake, and blunder, and the engine’s better moves.</p>
              </div>
            ) : (
              <>
                <SideSummary review={review} />
                <h2 className={styles.findingsTitle}>
                  Findings <span className={styles.count}>{findings.length}</span>
                </h2>
                {findings.length === 0 ? (
                  <p className={styles.emptyFindings}>No inaccuracies, mistakes, or blunders in these moves.</p>
                ) : (
                  <ol className={styles.findings}>
                    {findings.map((finding) => (
                      <FindingCard
                        key={finding.ply}
                        finding={finding}
                        selected={(showingFinding && focused?.ply === finding.ply) || line?.finding.ply === finding.ply}
                        activeLine={line?.finding.ply === finding.ply ? line.view : null}
                        onView={() => viewFinding(finding)}
                        onOpenLine={(source, step) => openLine(finding, source, step)}
                      />
                    ))}
                  </ol>
                )}
              </>
            )}
          </section>
        </div>
      )}
    </div>
  )
}

/** White's expected score and engine score at the end of an engine line, for the evaluation bar. */
function lineEvaluation(line: ResolvedLine): BarEvaluation {
  // Line scores are from the side to move at the line's start position.
  const startsWithWhite = line.firstPly % 2 === 1
  const sideToMove = 2 * line.line.win + line.line.draw
  const sign = startsWithWhite ? 1 : -1
  return {
    whiteExpected: startsWithWhite ? sideToMove : 2_000 - sideToMove,
    whiteCp: line.line.cp === null ? null : line.line.cp * sign,
    whiteMate: line.line.mate === null ? null : line.line.mate * sign,
  }
}

const COACH_QUESTIONS = [
  { key: 'why', label: 'Why?' },
  { key: 'threat', label: 'What was the threat?' },
  { key: 'train', label: 'What should I train?' },
] as const

/** Three fixed questions about the shown mistake, answered from the stored analysis. */
function CoachPanel({ card }: { card: CoachCard }) {
  const [question, setQuestion] = useState<(typeof COACH_QUESTIONS)[number]['key']>('why')
  return (
    <section className={styles.coach} aria-label="Coach">
      <div className={styles.coachQuestions} role="group" aria-label="Coach questions">
        {COACH_QUESTIONS.map((item) => (
          <button
            key={item.key}
            type="button"
            aria-pressed={question === item.key}
            onClick={() => setQuestion(item.key)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <div className={styles.coachAnswer} aria-live="polite">
        {question === 'train' ? (
          <ul>
            {card.train.map((item) => <li key={item}>{item}</li>)}
          </ul>
        ) : (
          <p>{card[question]}</p>
        )}
      </div>
    </section>
  )
}

/** The numbered moves of an engine line as buttons; step k shows the position after move k. */
function LineMoves({
  firstPly,
  sans,
  step,
  onSelect,
  label,
  limit,
}: {
  firstPly: number
  sans: string[]
  step: number | null
  onSelect: (step: number) => void
  label: string
  limit?: number
}) {
  const shown = limit === undefined ? sans : sans.slice(0, limit)
  return (
    <ol className={styles.lineMoves} aria-label={label}>
      {shown.map((san, offset) => {
        const ply = firstPly + offset
        const number = ply % 2 === 1 ? `${Math.ceil(ply / 2)}. ` : offset === 0 ? `${Math.ceil(ply / 2)}… ` : ''
        return (
          <li key={offset}>
            <button
              type="button"
              aria-current={step === offset + 1 ? 'step' : undefined}
              onClick={() => onSelect(offset + 1)}
              aria-label={moveLabel(ply, san)}
            >
              {number}{san}
            </button>
          </li>
        )
      })}
    </ol>
  )
}

function ReviewHeader({ review }: { review: Review }) {
  const { game } = review
  const opponent = game.opponent.title ? `${game.opponent.title} ${game.opponent.name}` : game.opponent.name
  return (
    <header className={styles.header}>
      <BackLink />
      <p className={styles.result} data-result={game.result}>{titleCase(game.result)}</p>
      <h2 className={styles.title}>
        <span>You ({titleCase(game.ownerColor)}{game.ownerRating === null ? '' : `, ${game.ownerRating}`})</span>
        <span className={styles.versus}>vs</span>
        <span>{opponent}{game.opponent.rating === null ? '' : ` (${game.opponent.rating})`}</span>
      </h2>
      <p className={styles.meta}>
        {formatDate(game.playedAt)} · {game.timeControl.label} {titleCase(game.speed)} · {game.rated ? 'Rated' : 'Casual'}
      </p>
      <p className={styles.meta}>
        {game.opening ? openingLabel(game.opening) : 'Opening unavailable'}
      </p>
    </header>
  )
}

function SideTabs({
  review,
  locked,
  onSelect,
  onAnalyze,
}: {
  review: Review
  locked: boolean
  onSelect: (side: ReviewSide) => void
  onAnalyze: (perspective: AnalysisPerspective) => void
}) {
  const sides: ReviewSide[] = ['my_moves', 'opponent_moves']
  const analyzed = (side: ReviewSide) => (side === 'my_moves' ? review.myMoves : review.opponentMoves) !== null
  const missing = sides.filter((side) => !analyzed(side))
  return (
    <div className={styles.sideControls}>
      <div className={styles.tabs} role="tablist" aria-label="Analyzed side">
        {sides.map((side) => (
          <button
            key={side}
            type="button"
            role="tab"
            aria-selected={review.selected?.side === side}
            disabled={!analyzed(side)}
            onClick={() => onSelect(side)}
          >
            {SIDE_TEXT[side]}
            {!analyzed(side) && <span className={styles.tabNote}> · Not analyzed</span>}
          </button>
        ))}
      </div>
      {review.game.analysis.eligible && missing.length > 0 && (
        <div className={styles.analyzeActions}>
          {missing.map((side) => (
            <button
              key={side}
              type="button"
              className={side === 'my_moves' ? styles.primaryButton : styles.secondaryButton}
              disabled={locked}
              onClick={() => onAnalyze(side)}
            >
              Analyze {SIDE_TEXT[side]}
            </button>
          ))}
          {missing.length === 2 && (
            <button type="button" className={styles.secondaryButton} disabled={locked} onClick={() => onAnalyze('both')}>
              Analyze Both Sides
            </button>
          )}
          {locked && <p className={styles.note}>Available when the current analysis finishes.</p>}
        </div>
      )}
    </div>
  )
}

function SideSummary({ review }: { review: Review }) {
  const selected = review.selected
  if (selected === null) return null
  const { statistics } = selected
  return (
    <div className={styles.summary}>
      <p className={styles.version}>
        {SIDE_TEXT[selected.side]} · {titleCase(selected.analyzedColor)} · {selected.engineName}
        {' · '}analyzed {formatDate(selected.completedAt)}
      </p>
      {!selected.currentProfile && (
        <p className={styles.note}>Earlier engine profile.</p>
      )}
      <dl className={styles.statistics}>
        <Stat label="Moves judged" value={statistics.moves.toString()} />
        <Stat label="Inaccuracies" value={statistics.inaccuracies.toString()} severity="inaccuracy" />
        <Stat label="Mistakes" value={statistics.mistakes.toString()} severity="mistake" />
        <Stat label="Blunders" value={statistics.blunders.toString()} severity="blunder" />
        <Stat label="Average loss" value={`${statistics.averageLoss.toFixed(1)} pts`} />
        <Stat label="Average centipawn loss" value={statistics.averageCentipawnLoss.toFixed(1)} />
      </dl>
      <div className={styles.tableScroll}>
      <table className={styles.phaseTable}>
        <caption>By phase{review.phases ? ` (${review.phases.source})` : ''}</caption>
        <thead>
          <tr>
            <th scope="col">Phase</th>
            <th scope="col">Moves</th>
            <th scope="col"><abbr title="Inaccuracies">Inacc.</abbr></th>
            <th scope="col">Mistakes</th>
            <th scope="col">Blunders</th>
            <th scope="col"><abbr title="Average expected-score loss in percentage points">Avg loss</abbr></th>
            <th scope="col"><abbr title="Average centipawn loss">ACPL</abbr></th>
          </tr>
        </thead>
        <tbody>
          {(['opening', 'middlegame', 'endgame'] as const).map((phase) => (
            <PhaseRow key={phase} phase={phase} statistics={statistics.byPhase[phase]} />
          ))}
        </tbody>
      </table>
      </div>
    </div>
  )
}

function PhaseRow({ phase, statistics }: { phase: Phase; statistics: PhaseStatistics }) {
  return (
    <tr>
      <th scope="row">{PHASE_TEXT[phase]}</th>
      <td>{statistics.moves}</td>
      <td>{statistics.inaccuracies}</td>
      <td>{statistics.mistakes}</td>
      <td>{statistics.blunders}</td>
      <td>{statistics.moves === 0 ? '—' : statistics.averageLoss.toFixed(1)}</td>
      <td>{statistics.moves === 0 ? '—' : statistics.averageCentipawnLoss.toFixed(1)}</td>
    </tr>
  )
}

function Stat({ label, value, severity }: { label: string; value: string; severity?: Severity }) {
  return (
    <div data-severity={severity}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

function MoveList({
  plies,
  index,
  judgedByPly,
  phases,
  onSelect,
}: {
  plies: Review['plies']
  index: number
  judgedByPly: Map<number, JudgedMove>
  phases: Review['phases']
  onSelect: (index: number) => void
}) {
  const rows: { number: number; white?: Review['plies'][number]; black?: Review['plies'][number] }[] = []
  for (const ply of plies) {
    const number = Math.ceil(ply.ply / 2)
    if (ply.color === 'white' || rows.length === 0 || rows[rows.length - 1].number !== number) {
      rows.push({ number })
    }
    rows[rows.length - 1][ply.color] = ply
  }
  const phaseStartsAt = (ply: number) => {
    // A move belongs to the phase of the position it was played from (index ply - 1).
    if (phases === null) return null
    if (ply - 1 === phases.middlegameStart && ply > 1) return 'Middlegame'
    if (phases.endgameStart !== null && ply - 1 === phases.endgameStart) return 'Endgame'
    return null
  }

  const cell = (ply: Review['plies'][number] | undefined) => {
    if (ply === undefined) return <span className={styles.moveEmpty} />
    const judged = judgedByPly.get(ply.ply)
    const severity = judged && judged.label !== 'none' ? judged.label : null
    return (
      <button
        type="button"
        className={styles.moveButton}
        aria-current={index === ply.ply ? 'step' : undefined}
        data-severity={severity ?? undefined}
        onClick={() => onSelect(ply.ply)}
        aria-label={`${moveLabel(ply.ply, ply.san)}${severity ? `, ${SEVERITY_TEXT[severity]}` : ''}`}
      >
        {ply.san}
        {severity && <span className={styles.symbol} aria-hidden="true">{SEVERITY_SYMBOL[severity]}</span>}
      </button>
    )
  }

  return (
    <ol className={styles.moveList}>
      {rows.map((row) => {
        const firstPly = row.white?.ply ?? row.black?.ply ?? 0
        const phaseStart = phaseStartsAt(firstPly) ?? (row.black ? phaseStartsAt(row.black.ply) : null)
        return (
          <li key={row.number} className={styles.moveRow}>
            {phaseStart && <span className={styles.phaseMarker}>{phaseStart}</span>}
            <span className={styles.moveNumber}>{row.number}.</span>
            {cell(row.white)}
            {cell(row.black)}
          </li>
        )
      })}
    </ol>
  )
}

/**
 * A vertical bar beside the board, filled with White's share of the expected score at the shown
 * position. The bottom of the bar is the side at the bottom of the board. A position without its
 * own evaluation (the start or final position, depending on the analyzed side) uses the nearest
 * evaluated position before it, or after it at the start of the game.
 */
type BarEvaluation = { whiteExpected: number; whiteCp: number | null; whiteMate: number | null }

function EvaluationBar({
  review,
  index,
  orientation,
  override,
}: {
  review: Review
  index: number
  orientation: BoardOrientation
  /** Shown instead of the game's evaluation, for an engine line. */
  override: BarEvaluation | null
}) {
  const evaluations = review.selected?.evaluations ?? []
  if (evaluations.length === 0) return null
  const sorted = [...evaluations].sort((a, b) => a.index - b.index)
  const evaluation: BarEvaluation = override
    ?? [...sorted].reverse().find((candidate) => candidate.index <= index)
    ?? sorted[0]
  const whitePercent = evaluation.whiteExpected / 20
  const score = barScore(evaluation)
  const whiteAhead = evaluation.whiteExpected >= 1000
  // The score sits at the end of the bar belonging to the side it favors.
  const scoreAtBottom = whiteAhead === (orientation === 'white')
  return (
    <div
      className={styles.evalBar}
      data-orientation={orientation}
      role="img"
      aria-label={`Evaluation bar: ${score} for White, White's expected score ${Math.round(whitePercent)} percent.`}
      data-testid="evaluation-bar"
    >
      <div className={styles.evalWhite} style={{ height: `${whitePercent}%` }} />
      <span className={styles.evalScore} data-position={scoreAtBottom ? 'bottom' : 'top'} data-side={whiteAhead ? 'white' : 'black'}>
        {score}
      </span>
    </div>
  )
}

function barScore(evaluation: BarEvaluation) {
  const engine = formatEngineScore(evaluation.whiteCp, evaluation.whiteMate)
  if (engine !== null) return engine
  // The game ended on the board: no engine score, only the result.
  if (evaluation.whiteExpected === 2_000) return '1-0'
  if (evaluation.whiteExpected === 0) return '0-1'
  return '½-½'
}

function FindingCard({
  finding,
  selected,
  activeLine,
  onView,
  onOpenLine,
}: {
  finding: JudgedMove
  selected: boolean
  activeLine: LineView | null
  onView: () => void
  onOpenLine: (source: LineView['source'], step: number) => void
}) {
  const severity = finding.label as Exclude<Severity, 'none'>
  const mover = finding.ply % 2 === 1 ? 'white' : 'black'
  const best = finding.before[0]
  const reply = finding.after[0]
  const bestSan = finding.bestMoveSan ?? finding.bestMove
  return (
    <li className={styles.finding} data-severity={severity} data-selected={selected}>
      <div className={styles.findingHeading}>
        <span className={styles.severity}>{SEVERITY_TEXT[severity]}</span>
        <span className={styles.notation}>{moveLabel(finding.ply, finding.san)}</span>
        <span className={styles.phase}>{PHASE_TEXT[finding.phase]}</span>
      </div>
      {finding.tactics.length > 0 && (
        <ul className={styles.tactics} aria-label="Tactic labels">
          {finding.tactics.map((tactic) => (
            <li key={`${tactic.theme}-${tactic.kind}`}>{tacticLabel(tactic)}</li>
          ))}
        </ul>
      )}
      <dl className={styles.findingFacts}>
        <div>
          <dt>Played</dt>
          <dd className={styles.notation}>{finding.san}</dd>
        </div>
        <div>
          <dt>Best</dt>
          <dd className={styles.notation}>{bestSan}</dd>
        </div>
        <div>
          <dt>Loss</dt>
          <dd>{(finding.loss / 20).toFixed(1)} pts</dd>
        </div>
        <div>
          <dt>Expected score</dt>
          <dd>{Math.round(finding.expectedBefore / 20)}% → {Math.round(finding.expectedAfter / 20)}%</dd>
        </div>
        {best && (
          <div>
            <dt>Engine (White)</dt>
            <dd className={styles.notation}>
              {formatLineScore(best, mover)} → {afterScore(finding, mover)}
            </dd>
          </div>
        )}
      </dl>
      {finding.mateEvent !== 'none' && (
        <p className={styles.mateEvent}>
          {finding.mateEvent === 'missed_mate' ? 'Missed a forced mate.' : 'Allowed a forced mate.'}
        </p>
      )}
      <div className={styles.findingActions}>
        <button
          type="button"
          className={styles.secondaryButton}
          aria-pressed={selected && activeLine === null}
          onClick={onView}
          aria-label={`View mistake ${moveLabel(finding.ply, finding.san)}`}
        >
          View Mistake
        </button>
        {best && best.pvSan.length > 0 && (
          <button
            type="button"
            className={styles.secondaryButton}
            aria-pressed={activeLine?.source === 'best'}
            onClick={() => onOpenLine('best', 1)}
            aria-label={`Show best move ${moveLabel(finding.ply, bestSan)}`}
          >
            Best Move: <span className={styles.notation}>{bestSan}</span>
          </button>
        )}
      </div>
      {best && best.pvSan.length > 0 && (
        <details className={styles.engineLine} open={activeLine !== null || undefined}>
          <summary>Show Engine Lines</summary>
          <p className={styles.lineTitle}>Best line</p>
          <LineMoves
            firstPly={finding.ply}
            sans={best.pvSan}
            step={activeLine?.source === 'best' ? activeLine.step : null}
            onSelect={(step) => onOpenLine('best', step)}
            label={`Best line for ${moveLabel(finding.ply, finding.san)}`}
            limit={16}
          />
          {reply && reply.pvSan.length > 0 && (
            <>
              <p className={styles.lineTitle}>After {finding.san}: the opponent’s best reply</p>
              <LineMoves
                firstPly={finding.ply + 1}
                sans={reply.pvSan}
                step={activeLine?.source === 'reply' ? activeLine.step : null}
                onSelect={(step) => onOpenLine('reply', step)}
                label={`Reply line after ${moveLabel(finding.ply, finding.san)}`}
                limit={16}
              />
            </>
          )}
        </details>
      )}
    </li>
  )
}

/** "12. Nf3" for White's move, "12… Nf6" for Black's. */
export function moveLabel(ply: number, san: string) {
  const number = Math.ceil(ply / 2)
  return ply % 2 === 1 ? `${number}. ${san}` : `${number}… ${san}`
}

/** Numbers a line of SAN moves starting at {@code firstPly}. */
export function formatVariation(firstPly: number, sans: string[]) {
  return sans
    .map((san, offset) => {
      const ply = firstPly + offset
      if (ply % 2 === 1) return `${Math.ceil(ply / 2)}. ${san}`
      return offset === 0 ? `${Math.ceil(ply / 2)}… ${san}` : san
    })
    .join(' ')
}

function formatLineScore(line: EngineLine, sideToMove: 'white' | 'black') {
  const sign = sideToMove === 'white' ? 1 : -1
  return formatEngineScore(line.cp === null ? null : line.cp * sign, line.mate === null ? null : line.mate * sign) ?? '—'
}

function afterScore(finding: JudgedMove, mover: 'white' | 'black') {
  if (finding.afterTerminal === 'checkmate') return 'Checkmate'
  if (finding.afterTerminal === 'stalemate') return 'Stalemate'
  const line = finding.after[0]
  if (!line) return '—'
  return formatLineScore(line, mover === 'white' ? 'black' : 'white')
}

function formatEngineScore(cp: number | null, mate: number | null) {
  if (mate !== null) return mate > 0 ? `#${mate}` : `#−${Math.abs(mate)}`
  if (cp === null) return null
  const pawns = (cp / 100).toFixed(1)
  return cp > 0 ? `+${pawns}` : cp < 0 ? `−${pawns.slice(1)}` : '0.0'
}

function titleCase(value: string) {
  return value.charAt(0).toUpperCase() + value.slice(1)
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(value))
}
