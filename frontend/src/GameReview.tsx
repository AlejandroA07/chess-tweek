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
import { GameAnalysisPanel } from './GameAnalysisPanel'
import type { AnalysisPerspective } from './analysis'
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

/** One stored game: board, moves, evaluation progression, and one analyzed side's findings. */
export function GameReview({
  gameId,
  onSessionFailure,
}: {
  gameId: string
  onSessionFailure: (failure: 'not_connected' | 'session_unavailable') => void
}) {
  const [side, setSide] = useState<ReviewSide | null>(null)
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
    requestReview(gameId, side, controller.signal)
      .then((review) => {
        setState({ kind: 'ready', review })
        setIndex((current) => Math.min(current, review.plies.length))
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
  }, [gameId, side, reloadTick, onSessionFailure])

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
        setIndex(move.ply - 1)
        setFocusedPly(move.ply)
        boardPanel.current?.focus()
      }}
      onSelectSide={(next) => {
        setSide(next)
        setFocusedPly(null)
      }}
      boardPanel={boardPanel}
      analysisPanel={(
        <GameAnalysisPanel
          job={analysis.job?.gameId === review.game.id || analysis.job === null ? analysis.job : null}
          opponentName={review.game.opponent.name}
          starting={analysis.starting}
          canceling={analysis.canceling}
          startFailure={analysis.startFailure}
          now={Date.now()}
          onCancel={analysis.cancel}
          onRetry={() => analysis.start(review.game.id, lastPerspective.current)}
          onDismiss={analysis.dismiss}
        />
      )}
      analysisLocked={analysis.starting || analysis.job?.state === 'running'}
      onAnalyze={(perspective) => {
        lastPerspective.current = perspective
        if (perspective !== 'both') setSide(perspective)
        analysis.start(review.game.id, perspective)
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
      text: 'The stored analysis could not be read. Try again; if it repeats, reanalyze the game from Games.',
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
  const showingFinding = focused !== null && index === focused.ply - 1
  const fen = index === 0 ? review.initialFen : plies[index - 1].fen
  const lastPly = index === 0 ? null : plies[index - 1]

  const move = (next: number) => onIndexChange(Math.max(0, Math.min(plies.length, next)))
  const onBoardKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys: Record<string, () => void> = {
      ArrowLeft: () => move(index - 1),
      ArrowRight: () => move(index + 1),
      Home: () => move(0),
      End: () => move(plies.length),
    }
    const action = keys[event.key]
    if (action) {
      event.preventDefault()
      action()
    }
  }

  const positionText = showingFinding && focused
    ? `Position before ${moveLabel(focused.ply, focused.san)}. Best move ${focused.bestMoveSan ?? focused.bestMove}.`
    : lastPly === null
      ? 'Starting position.'
      : `Position after ${moveLabel(lastPly.ply, lastPly.san)}.`

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
            <div
              ref={boardPanel}
              className={styles.boardPanel}
              tabIndex={0}
              onKeyDown={onBoardKey}
              aria-label="Board navigation. Use the arrow keys, Home, and End to move through the game."
            >
              <ChessBoard
                fen={fen}
                orientation={orientation}
                label={`${positionText} ${orientation === 'white' ? 'White' : 'Black'} at the bottom.`}
                highlight={showingFinding ? null : lastPly?.uci ?? null}
                arrow={showingFinding && focused ? focused.bestMove : null}
              />
            </div>
            <p className={styles.positionText} aria-live="polite">{positionText}</p>
            {showingFinding && focused && (
              <p className={styles.findingCaption}>
                Played <span className={styles.notation}>{focused.san}</span>
                {' · '}Best <span className={styles.notation}>{focused.bestMoveSan ?? focused.bestMove}</span>
                {' · '}
                <button className={styles.textButton} type="button" onClick={() => move(focused.ply)}>
                  Show the played move
                </button>
              </p>
            )}
            <div className={styles.boardControls} role="group" aria-label="Move navigation">
              <button type="button" onClick={() => move(0)} disabled={index === 0} aria-label="First position">|&lt;</button>
              <button type="button" onClick={() => move(index - 1)} disabled={index === 0} aria-label="Previous move">&lt;</button>
              <button type="button" onClick={() => move(index + 1)} disabled={index === plies.length} aria-label="Next move">&gt;</button>
              <button type="button" onClick={() => move(plies.length)} disabled={index === plies.length} aria-label="Last position">&gt;|</button>
              <button type="button" className={styles.flipButton} onClick={onFlip}>Flip Board</button>
            </div>
            <p className={styles.orientation}>
              {orientation === 'white' ? 'White' : 'Black'} at the bottom
            </p>
            <EvaluationGraph review={review} index={index} />
          </section>

          <section className={styles.movesColumn} aria-label="Moves">
            <h2>Moves</h2>
            <MoveList
              plies={plies}
              index={index}
              judgedByPly={judgedByPly}
              phases={review.phases}
              onSelect={move}
            />
          </section>

          <section className={styles.analysisColumn} aria-label="Analysis">
            <SideTabs
              review={review}
              locked={analysisLocked}
              onSelect={onSelectSide}
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
                        selected={showingFinding && focused?.ply === finding.ply}
                        onView={() => onViewFinding(finding)}
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
        {game.opening ? `${game.opening.eco} · ${game.opening.name}` : 'Opening unavailable'}
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
        <p className={styles.note}>Earlier engine profile · reanalyze from Games to update.</p>
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

function EvaluationGraph({ review, index }: { review: Review; index: number }) {
  const evaluations = review.selected?.evaluations ?? []
  if (evaluations.length < 2) return null
  const width = Math.max(1, review.plies.length)
  const points = [...evaluations]
    .sort((a, b) => a.index - b.index)
    .map((evaluation) => `${evaluation.index},${(2_000 - evaluation.whiteExpected) / 20}`)
  const current = evaluations.find((evaluation) => evaluation.index === index)
  const phases = review.phases
  return (
    <figure className={styles.graph}>
      <svg
        viewBox={`0 0 ${width} 100`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`Evaluation progression over ${evaluations.length} positions. ${
          current ? `White's expected score here: ${Math.round(current.whiteExpected / 20)} percent.` : 'No evaluation for this position.'
        }`}
      >
        <rect className={styles.graphBlack} x="0" y="0" width={width} height="100" />
        <polygon
          className={styles.graphWhite}
          points={`${points[0].split(',')[0]},100 ${points.join(' ')} ${points[points.length - 1].split(',')[0]},100`}
        />
        <line className={styles.graphMidline} x1="0" x2={width} y1="50" y2="50" />
        {phases && phases.middlegameStart > 0 && (
          <line className={styles.graphPhase} x1={phases.middlegameStart} x2={phases.middlegameStart} y1="0" y2="100" />
        )}
        {phases && phases.endgameStart !== null && (
          <line className={styles.graphPhase} x1={phases.endgameStart} x2={phases.endgameStart} y1="0" y2="100" />
        )}
        <line className={styles.graphCursor} x1={index} x2={index} y1="0" y2="100" />
      </svg>
      <figcaption>
        Evaluation for White · {current ? `${Math.round(current.whiteExpected / 20)}% here` : 'not evaluated here'}
        {current && formatEngineScore(current.whiteCp, current.whiteMate) !== null && ` · ${formatEngineScore(current.whiteCp, current.whiteMate)}`}
      </figcaption>
    </figure>
  )
}

function FindingCard({
  finding,
  selected,
  onView,
}: {
  finding: JudgedMove
  selected: boolean
  onView: () => void
}) {
  const severity = finding.label as Exclude<Severity, 'none'>
  const mover = finding.ply % 2 === 1 ? 'white' : 'black'
  const best = finding.before[0]
  return (
    <li className={styles.finding} data-severity={severity} data-selected={selected}>
      <div className={styles.findingHeading}>
        <span className={styles.severity}>{SEVERITY_TEXT[severity]}</span>
        <span className={styles.notation}>{moveLabel(finding.ply, finding.san)}</span>
        <span className={styles.phase}>{PHASE_TEXT[finding.phase]}</span>
      </div>
      <dl className={styles.findingFacts}>
        <div>
          <dt>Played</dt>
          <dd className={styles.notation}>{finding.san}</dd>
        </div>
        <div>
          <dt>Best</dt>
          <dd className={styles.notation}>{finding.bestMoveSan ?? finding.bestMove}</dd>
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
          aria-pressed={selected}
          onClick={onView}
          aria-label={`View position before ${moveLabel(finding.ply, finding.san)}`}
        >
          View Position
        </button>
      </div>
      {best && best.pvSan.length > 0 && (
        <details className={styles.engineLine}>
          <summary>Show Engine Line</summary>
          <p className={styles.notation}>{formatVariation(finding.ply, best.pvSan)}</p>
          {finding.after[0] && finding.after[0].pvSan.length > 0 && (
            <p className={styles.refutation}>
              After {finding.san}: <span className={styles.notation}>{formatVariation(finding.ply + 1, finding.after[0].pvSan)}</span>
            </p>
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
