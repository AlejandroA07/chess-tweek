/**
 * Error codes the Puzzle, Own Puzzle, and guided-course endpoints return in `{"error": ...}`.
 * They mirror `TrainingErrorCodes.java`; `TrainingErrorCodesTest` fails if this list names a code
 * the server does not define.
 */
export const SERVER_ERROR = {
  sessionUnavailable: 'session_unavailable',
  trainingUnavailable: 'training_unavailable',
  illegalMove: 'illegal_training_move',
  attemptNotFound: 'attempt_not_found',
  attemptFinished: 'attempt_finished',
  noPuzzleAvailable: 'no_puzzle_available',
  sessionComplete: 'session_complete',
  sessionNotFound: 'session_not_found',
  ownPuzzleNotFound: 'own_puzzle_not_found',
  ownPuzzleClassified: 'own_puzzle_classified',
  ownPuzzleNotDiscarded: 'own_puzzle_not_discarded',
  noNewOwnPuzzles: 'no_new_own_puzzles',
  patternNotFound: 'pattern_not_found',
  ladderComplete: 'ladder_complete',
} as const
