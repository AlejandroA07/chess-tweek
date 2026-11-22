import { parseAttempt, parseCompletion, type PuzzleAttempt, type PuzzleCompletion } from './puzzles'
import { SERVER_ERROR } from './trainingErrorCodes'

export type Course = {
  key: string
  version: number
  title: string
  subtitle: string
  attribution: string
  modules: CourseModule[]
}

export type CourseModule = {
  key: string
  title: string
  description: string
  status: 'available' | 'planned'
  lessons: Lesson[]
}

export type Lesson = {
  key: string
  title: string
  summary: string
  estimatedMinutes: number
  explanation: string[]
  checklist: string[]
  exercises: Exercise[]
}

export type Exercise = {
  key: string
  fen: string
  orientation: 'white' | 'black'
  difficultyTier: 'foundation' | 'development' | 'stretch'
  sourceRating: number
  prompt: string
}

export type Progress = {
  courseKey: string
  contentVersion: number
  completedLessons: number
  totalLessons: number
  recommendedLessonKey: string
  lessons: Record<string, LessonProgress>
}

export type LessonProgress = {
  state: 'not_started' | 'in_progress' | 'completed'
  exercises: Record<string, ExerciseProgress>
}

export type ExerciseProgress = {
  state: 'not_started' | 'in_progress' | 'solved' | 'revealed'
  attempts: number
  hintLevel: number
}

export type ExerciseMoveResult =
  | { outcome: 'correct'; opponentReply: string; wrongMoves: number }
  | { outcome: 'wrong'; wrongMoves: number; feedback: string | null }
  | {
    outcome: 'solved'
    wrongMoves: number
    feedback: string | null
    explanation: string
    completion: PuzzleCompletion
  }

export class TrainingRequestError extends Error {
  constructor(
    readonly code:
      | 'not_connected'
      | 'session_unavailable'
      | 'unavailable'
      | 'incomplete'
      | 'attempt_finished'
      | 'illegal_move',
  ) {
    super(code)
  }
}

const base = '/api/training/courses/grandmaster-thinking-method'

export async function loadCourse(signal?: AbortSignal): Promise<Course> {
  const response = await fetch(base, { credentials: 'same-origin', signal })
  if (!response.ok) throw new TrainingRequestError('unavailable')
  return parseCourse(await response.json())
}

export async function loadProgress(signal?: AbortSignal): Promise<Progress> {
  const response = await fetch(`${base}/progress`, { credentials: 'same-origin', signal })
  await requireResponse(response)
  return parseProgress(await response.json())
}

export async function startLesson(lessonKey: string): Promise<Progress> {
  const response = await send(`${base}/lessons/${encodeURIComponent(lessonKey)}/start`)
  return parseProgress(await response.json())
}

/** Starts a full Puzzle attempt on the Exercise's Puzzle; its Solve Time starts now. */
export async function startExercise(exerciseKey: string): Promise<PuzzleAttempt> {
  const response = await send(`${base}/exercises/${encodeURIComponent(exerciseKey)}/attempts`)
  const value: unknown = await response.json()
  return asCourseFailure(() => parseAttempt(value))
}

export async function submitExerciseMove(
  exerciseKey: string,
  attemptId: number,
  move: string,
): Promise<ExerciseMoveResult> {
  const response = await send(`${attemptPath(exerciseKey, attemptId)}/moves`, { move })
  const value: unknown = await response.json()
  if (
    !isRecord(value) ||
    (value.outcome !== 'correct' && value.outcome !== 'solved' && value.outcome !== 'wrong') ||
    !isInteger(value.wrongMoves, 0, 10_000) ||
    !(value.feedback === null || value.feedback === undefined || isText(value.feedback, 300)) ||
    !(value.explanation === null || value.explanation === undefined || isText(value.explanation, 500))
  ) throw new TrainingRequestError('unavailable')
  const feedback = typeof value.feedback === 'string' ? value.feedback : null
  if (value.outcome === 'correct') {
    if (!isUci(value.opponentReply)) throw new TrainingRequestError('unavailable')
    return { outcome: 'correct', opponentReply: value.opponentReply, wrongMoves: value.wrongMoves }
  }
  if (value.outcome === 'wrong') {
    return { outcome: 'wrong', wrongMoves: value.wrongMoves, feedback }
  }
  if (typeof value.explanation !== 'string') throw new TrainingRequestError('unavailable')
  const completion = asCourseFailure(() => parseCompletion(value.completion))
  return { outcome: 'solved', wrongMoves: value.wrongMoves, feedback, explanation: value.explanation, completion }
}

/** A written hint; it counts as a hint for the Puzzle's Training List. */
export async function requestExerciseHint(exerciseKey: string, attemptId: number, level: number) {
  const response = await send(`${attemptPath(exerciseKey, attemptId)}/hint`, { level })
  const value: unknown = await response.json()
  if (!isRecord(value) || !isInteger(value.level, 1, 2) || !isText(value.text, 180)) {
    throw new TrainingRequestError('unavailable')
  }
  return { level: value.level, text: value.text }
}

/** Show solution: completes the attempt as hinted and returns the remaining line. */
export async function revealExercise(exerciseKey: string, attemptId: number) {
  const response = await send(`${attemptPath(exerciseKey, attemptId)}/reveal`)
  const value: unknown = await response.json()
  if (
    !isRecord(value) || !isUciLine(value.remainingMoves) || !isText(value.explanation, 500)
  ) throw new TrainingRequestError('unavailable')
  return {
    remainingMoves: value.remainingMoves,
    explanation: value.explanation,
    completion: asCourseFailure(() => parseCompletion(value.completion)),
  }
}

/** The full answer of an Exercise already solved or revealed; it changes nothing. */
export async function loadExerciseAnswer(exerciseKey: string) {
  const response = await fetch(`${base}/exercises/${encodeURIComponent(exerciseKey)}/answer`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  await requireResponse(response)
  const value: unknown = await response.json()
  if (
    !isRecord(value) || !isUciLine(value.solutionLine) || !isText(value.explanation, 500)
  ) throw new TrainingRequestError('unavailable')
  return { solutionLine: value.solutionLine, explanation: value.explanation }
}

/** A non-empty line of at most 200 UCI moves. */
function isUciLine(value: unknown): value is string[] {
  return Array.isArray(value) && value.length >= 1 && value.length <= 200 && value.every(isUci)
}

function attemptPath(exerciseKey: string, attemptId: number) {
  if (!isInteger(attemptId, 1, Number.MAX_SAFE_INTEGER)) throw new TrainingRequestError('unavailable')
  return `${base}/exercises/${encodeURIComponent(exerciseKey)}/attempts/${attemptId}`
}

/** Runs a shared Puzzle parser; any parse failure is reported as a course request failure. */
function asCourseFailure<T>(parse: () => T): T {
  try {
    return parse()
  } catch {
    throw new TrainingRequestError('unavailable')
  }
}

export async function completeLesson(lessonKey: string): Promise<Progress> {
  const response = await fetch(`${base}/lessons/${encodeURIComponent(lessonKey)}/complete`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  })
  if (response.status === 409) throw new TrainingRequestError('incomplete')
  await requireResponse(response)
  return parseProgress(await response.json())
}

async function send(url: string, body?: unknown) {
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  await requireResponse(response)
  return response
}

async function requireResponse(response: Response) {
  if (response.ok) return
  if (response.status === 401) throw new TrainingRequestError('not_connected')
  const code = await errorCode(response)
  if (code === SERVER_ERROR.attemptFinished || code === SERVER_ERROR.attemptNotFound) {
    throw new TrainingRequestError('attempt_finished')
  }
  if (code === SERVER_ERROR.illegalMove) throw new TrainingRequestError('illegal_move')
  if (response.status === 503 && code !== SERVER_ERROR.trainingUnavailable) {
    throw new TrainingRequestError('session_unavailable')
  }
  throw new TrainingRequestError('unavailable')
}

async function errorCode(response: Response) {
  try {
    const value: unknown = await response.json()
    return isRecord(value) && typeof value.error === 'string' ? value.error : null
  } catch {
    return null
  }
}

function parseCourse(value: unknown): Course {
  if (
    !isRecord(value) ||
    value.key !== 'grandmaster-thinking-method' ||
    !isInteger(value.version, 1, 1000) ||
    !isText(value.title, 100) ||
    !isText(value.subtitle, 180) ||
    !isText(value.attribution, 400) ||
    !Array.isArray(value.modules) ||
    value.modules.length < 1 ||
    value.modules.length > 20
  ) throw new TrainingRequestError('unavailable')
  return {
    key: value.key,
    version: value.version,
    title: value.title,
    subtitle: value.subtitle,
    attribution: value.attribution,
    modules: value.modules.map(parseModule),
  }
}

function parseModule(value: unknown): CourseModule {
  if (
    !isRecord(value) || !isKey(value.key) || !isText(value.title, 100) ||
    !isText(value.description, 300) ||
    (value.status !== 'available' && value.status !== 'planned') ||
    !Array.isArray(value.lessons) || value.lessons.length > 20
  ) throw new TrainingRequestError('unavailable')
  return {
    key: value.key,
    title: value.title,
    description: value.description,
    status: value.status,
    lessons: value.lessons.map(parseLesson),
  }
}

function parseLesson(value: unknown): Lesson {
  if (
    !isRecord(value) || !isKey(value.key) || !isText(value.title, 100) ||
    !isText(value.summary, 300) || !isInteger(value.estimatedMinutes, 1, 120) ||
    !isTextArray(value.explanation, 8, 500) || !isTextArray(value.checklist, 8, 180) ||
    !Array.isArray(value.exercises) || value.exercises.length < 1 || value.exercises.length > 20
  ) throw new TrainingRequestError('unavailable')
  return {
    key: value.key,
    title: value.title,
    summary: value.summary,
    estimatedMinutes: value.estimatedMinutes,
    explanation: value.explanation,
    checklist: value.checklist,
    exercises: value.exercises.map(parseExercise),
  }
}

function parseExercise(value: unknown): Exercise {
  if (
    !isRecord(value) || !isKey(value.key) || !isText(value.fen, 100) ||
    (value.orientation !== 'white' && value.orientation !== 'black') ||
    (value.difficultyTier !== 'foundation' && value.difficultyTier !== 'development' &&
      value.difficultyTier !== 'stretch') ||
    !isInteger(value.sourceRating, 100, 4000) ||
    !isText(value.prompt, 300)
  ) throw new TrainingRequestError('unavailable')
  return {
    key: value.key,
    fen: value.fen,
    orientation: value.orientation,
    difficultyTier: value.difficultyTier,
    sourceRating: value.sourceRating,
    prompt: value.prompt,
  }
}

function parseProgress(value: unknown): Progress {
  if (
    !isRecord(value) || value.courseKey !== 'grandmaster-thinking-method' ||
    !isInteger(value.contentVersion, 1, 1000) ||
    !isInteger(value.completedLessons, 0, 100) ||
    !isInteger(value.totalLessons, 1, 100) ||
    value.completedLessons > value.totalLessons ||
    !isKey(value.recommendedLessonKey) || !isRecord(value.lessons)
  ) throw new TrainingRequestError('unavailable')
  const lessons: Record<string, LessonProgress> = {}
  for (const [key, lesson] of Object.entries(value.lessons)) {
    if (!isKey(key) || !isRecord(lesson) ||
        (lesson.state !== 'not_started' && lesson.state !== 'in_progress' && lesson.state !== 'completed') ||
        !isRecord(lesson.exercises)) throw new TrainingRequestError('unavailable')
    const exercises: Record<string, ExerciseProgress> = {}
    for (const [exerciseKey, exercise] of Object.entries(lesson.exercises)) {
      if (!isKey(exerciseKey) || !isRecord(exercise) ||
          (exercise.state !== 'not_started' && exercise.state !== 'in_progress' &&
            exercise.state !== 'solved' && exercise.state !== 'revealed') ||
          !isInteger(exercise.attempts, 0, 10_000) || !isInteger(exercise.hintLevel, 0, 2)) {
        throw new TrainingRequestError('unavailable')
      }
      exercises[exerciseKey] = {
        state: exercise.state,
        attempts: exercise.attempts,
        hintLevel: exercise.hintLevel,
      }
    }
    lessons[key] = { state: lesson.state, exercises }
  }
  return {
    courseKey: value.courseKey,
    contentVersion: value.contentVersion,
    completedLessons: value.completedLessons,
    totalLessons: value.totalLessons,
    recommendedLessonKey: value.recommendedLessonKey,
    lessons,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maximum
}

function isKey(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 64 && /^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(value)
}

function isUci(value: unknown): value is string {
  return typeof value === 'string' && /^[a-h][1-8][a-h][1-8][qrbn]?$/.test(value)
}

function isInteger(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum
}

function isTextArray(value: unknown, maximumCount: number, maximumLength: number): value is string[] {
  return Array.isArray(value) && value.length > 0 && value.length <= maximumCount &&
    value.every((entry) => isText(entry, maximumLength))
}
