import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChessBoard, InteractiveChessBoard } from './ChessBoard'
import styles from './ChessBoard.module.css'

const fen = '4k3/8/8/8/8/8/4r3/4K3 w - - 0 1'

describe('InteractiveChessBoard', () => {
  it('uses the shared square layout for every interactive cell', () => {
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={vi.fn()}
      />,
    )

    const boardGroup = screen.getByRole('group', { name: 'Training position' })
    expect(boardGroup.firstElementChild).toHaveClass(styles.board)
    expect(boardGroup.querySelectorAll(`button.${styles.moveSquare}`)).toHaveLength(64)
    expect(screen.getByRole('button', { name: 'Draw arrow' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, white king' })).toHaveClass(styles.square, styles.moveSquare)
  })

  it('replays a played line from the starting move and marks a hint square', () => {
    const puzzleFen = '5rk1/5pb1/B1p4p/2p5/8/5q1P/PQ4P1/R5K1 w - - 0 27'
    const onMove = vi.fn()
    const { rerender } = render(
      <InteractiveChessBoard
        fen={puzzleFen}
        orientation="white"
        label="Puzzle"
        disabled={false}
        solution={null}
        initialLastMove="g3f3"
        hintSquare="b2"
        onMove={onMove}
      />,
    )
    expect(screen.getByRole('button', { name: 'f3, black queen' })).toHaveAttribute('data-last-move', 'to')
    expect(screen.getByRole('button', { name: 'b2, white queen, hint: move this piece' }))
      .toHaveAttribute('data-hint', 'true')

    rerender(
      <InteractiveChessBoard
        fen={puzzleFen}
        orientation="white"
        label="Puzzle"
        disabled={false}
        solution={null}
        line={['b2g7', 'g8g7']}
        initialLastMove="g3f3"
        onMove={onMove}
      />,
    )
    expect(screen.getByRole('button', { name: 'g7, black king' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'b2, empty' })).not.toHaveAttribute('data-hint', 'true')
    expect(screen.getByRole('button', { name: 'g8, empty' })).toHaveAttribute('data-last-move', 'from')
    expect(screen.getByText(/History 3 of 3/)).toBeInTheDocument()
  })

  it('keeps the existing review board read-only', () => {
    const { container } = render(
      <ChessBoard fen={fen} orientation="white" label="Review position" highlight="e2e1" arrow={null} />,
    )

    expect(screen.getByRole('img', { name: 'Review position' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(container.querySelector('[data-square="e2"]')).toHaveAttribute('data-last-move', 'from')
    expect(container.querySelector('[data-square="e1"]')).toHaveAttribute('data-last-move', 'to')
  })

  it('submits the same UCI move by square selection and drag', () => {
    const onMove = vi.fn()
    const { unmount } = render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={onMove}
      />,
    )

    const king = screen.getByRole('button', { name: 'e1, white king' })
    const rook = screen.getByRole('button', { name: 'e2, black rook' })
    fireEvent.click(king)
    expect(king).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(rook)
    expect(onMove).toHaveBeenLastCalledWith('e1e2')

    unmount()
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={onMove}
      />,
    )
    const draggedKing = screen.getByRole('button', { name: 'e1, white king' })
      .querySelector('span')
    expect(draggedKing).not.toBeNull()
    const dropTarget = screen.getByRole('button', { name: 'e2, black rook' })
    fireEvent.dragStart(draggedKing!)
    fireEvent.dragOver(dropTarget)
    fireEvent.drop(dropTarget)
    expect(onMove).toHaveBeenLastCalledWith('e1e2')
  })

  it('shows the submitted move without drawing an automatic answer arrow', async () => {
    const onMove = vi.fn()
    const { rerender } = render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={onMove}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'e1, white king' }))
    fireEvent.click(screen.getByRole('button', { name: 'e2, black rook' }))
    expect(await screen.findByRole('button', { name: 'e1, empty' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e2, white king' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, empty' })).toHaveAttribute('data-last-move', 'from')
    expect(screen.getByRole('button', { name: 'e2, white king' })).toHaveAttribute('data-last-move', 'to')
    rerender(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution="e1e2"
        onMove={onMove}
      />,
    )

    expect(screen.getByRole('button', { name: 'e1, empty' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e2, white king' })).toBeInTheDocument()
    expect(screen.queryByTestId('best-move-arrow')).not.toBeInTheDocument()
  })

  it('continues with local moves after the answer and scrolls through board history', () => {
    const onMove = vi.fn()
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution="e1e2"
        onMove={onMove}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'e8, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'd8, empty' }))
    expect(screen.getByRole('button', { name: 'd8, black king' })).toBeInTheDocument()
    expect(onMove).not.toHaveBeenCalled()

    const board = screen.getByRole('group', { name: 'Training position' }).firstElementChild!
    fireEvent.wheel(board, { deltaY: -100 })
    expect(screen.getByRole('button', { name: 'e8, black king' })).toBeInTheDocument()
    fireEvent.wheel(board, { deltaY: -100 })
    expect(screen.getByRole('button', { name: 'e1, white king' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e2, black rook' })).toBeInTheDocument()
    fireEvent.wheel(board, { deltaY: 100 })
    fireEvent.wheel(board, { deltaY: 100 })
    expect(screen.getByRole('button', { name: 'd8, black king' })).toBeInTheDocument()
  })

  it('rejects illegal king exploration moves after the answer', () => {
    const onMove = vi.fn()
    render(
      <InteractiveChessBoard
        fen="4k3/8/8/8/8/8/P7/K3R3 w - - 0 1"
        orientation="white"
        label="Legal exploration"
        disabled={false}
        solution="a2a3"
        onMove={onMove}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'e8, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'd6, empty' }))
    expect(screen.getByRole('button', { name: 'e8, black king' })).toBeInTheDocument()
    expect(screen.getByText('That move is not legal in this position')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'e8, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'e7, empty' }))
    expect(screen.getByRole('button', { name: 'e8, black king' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'e8, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'd8, empty' }))
    expect(screen.getByRole('button', { name: 'd8, black king' })).toBeInTheDocument()
    expect(onMove).not.toHaveBeenCalled()
  })

  it('rejects blocked exploration moves without consuming the turn', () => {
    render(
      <InteractiveChessBoard
        fen="4k3/8/8/8/K7/8/P3p3/4r3 w - - 0 1"
        orientation="white"
        label="Blocked exploration"
        disabled={false}
        solution="a2a3"
        onMove={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'e1, black rook' }))
    fireEvent.click(screen.getByRole('button', { name: 'e3, empty' }))
    expect(screen.getByRole('button', { name: 'e1, black rook' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'e8, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'd8, empty' }))
    expect(screen.getByRole('button', { name: 'd8, black king' })).toBeInTheDocument()
  })

  it('preserves castling and en passant state during exploration', () => {
    const { unmount } = render(
      <InteractiveChessBoard
        fen="4k2r/8/8/8/8/8/P7/K7 w k - 0 1"
        orientation="white"
        label="Castling exploration"
        disabled={false}
        solution="a2a3"
        onMove={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'e8, black king' }))
    fireEvent.click(screen.getByRole('button', { name: 'g8, empty' }))
    expect(screen.getByRole('button', { name: 'g8, black king' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'f8, black rook' })).toBeInTheDocument()

    unmount()
    render(
      <InteractiveChessBoard
        fen="4k3/8/8/8/3p4/8/4P3/4K3 w - - 0 1"
        orientation="white"
        label="En passant exploration"
        disabled={false}
        solution="e2e4"
        onMove={vi.fn()}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'd4, black pawn' }))
    fireEvent.click(screen.getByRole('button', { name: 'e3, empty' }))
    expect(screen.getByRole('button', { name: 'e3, black pawn' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e4, empty' })).toBeInTheDocument()
  })

  it('moves optimistically and restores a rejected attempt', async () => {
    let finish: ((accepted: boolean) => void) | undefined
    const onMove = vi.fn(() => new Promise<boolean>((resolve) => { finish = resolve }))
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={onMove}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'e1, white king' }))
    fireEvent.click(screen.getByRole('button', { name: 'e2, black rook' }))
    expect(screen.getByRole('button', { name: 'e1, empty' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e2, white king' })).toBeInTheDocument()

    finish?.(false)
    await waitFor(() => expect(screen.getByRole('button', { name: 'e1, white king' })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'e2, black rook' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, white king' })).toHaveAttribute('data-last-move', 'none')
    expect(screen.getByRole('button', { name: 'e2, black rook' })).toHaveAttribute('data-last-move', 'none')
  })

  it('stacks right-drag arrows and clears them on the next normal left interaction', () => {
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={vi.fn()}
      />,
    )

    expect(screen.queryByTestId('user-drawn-arrow')).not.toBeInTheDocument()
    fireEvent.mouseDown(screen.getByRole('button', { name: 'e1, white king' }), { button: 2 })
    fireEvent.mouseUp(screen.getByRole('button', { name: 'e2, black rook' }), { button: 2 })

    fireEvent.mouseDown(screen.getByRole('button', { name: 'e1, white king' }), { button: 2 })
    fireEvent.mouseUp(screen.getByRole('button', { name: 'd1, empty' }), { button: 2 })

    expect(screen.getByTestId('user-drawn-arrow').querySelectorAll('line')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'e1, white king' }))
    expect(screen.queryByTestId('user-drawn-arrow')).not.toBeInTheDocument()
  })

  it('accepts chess movement geometry from empty squares and bends knight arrows', () => {
    const knightFen = '4k3/8/8/8/8/8/8/4K3 w - - 0 1'
    render(
      <InteractiveChessBoard
        fen={knightFen}
        orientation="white"
        label="Knight annotations"
        disabled={false}
        solution={null}
        onMove={vi.fn()}
      />,
    )

    fireEvent.mouseDown(screen.getByRole('button', { name: 'b1, empty' }), { button: 2 })
    fireEvent.mouseUp(screen.getByRole('button', { name: 'c4, empty' }), { button: 2 })
    expect(screen.queryByTestId('user-drawn-arrow')).not.toBeInTheDocument()
    expect(screen.getByText('That arrow does not match a chess move')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByRole('button', { name: 'b1, empty' }), { button: 2 })
    fireEvent.mouseUp(screen.getByRole('button', { name: 'c3, empty' }), { button: 2 })
    const overlay = screen.getByTestId('user-drawn-arrow')
    expect(overlay.querySelectorAll('polyline')).toHaveLength(1)
    expect(overlay.querySelectorAll('line')).toHaveLength(0)
    expect(overlay.querySelector('polyline')?.getAttribute('points')?.trim().split(/\s+/))
      .toHaveLength(3)
  })

  it('allows straight and diagonal annotations through occupied squares', () => {
    render(
      <InteractiveChessBoard
        fen="4k3/8/8/8/8/8/P7/R3K3 w Q - 0 1"
        orientation="white"
        label="Blocked annotation"
        disabled={false}
        solution={null}
        onMove={vi.fn()}
      />,
    )

    fireEvent.mouseDown(screen.getByRole('button', { name: 'a1, white rook' }), { button: 2 })
    fireEvent.mouseUp(screen.getByRole('button', { name: 'a8, empty' }), { button: 2 })
    expect(screen.getByTestId('user-drawn-arrow').querySelectorAll('line')).toHaveLength(1)
  })

  it('offers a keyboard-operable arrow mode without submitting a move', () => {
    const onMove = vi.fn()
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Training position"
        disabled={false}
        solution={null}
        onMove={onMove}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Draw arrow' }))
    expect(screen.getByText('Choose the start square')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'e1, white king' }))
    expect(screen.getByText('Choose the end square')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'e2, black rook' }))

    expect(screen.getByTestId('user-drawn-arrow')).toBeInTheDocument()
    expect(screen.getByText('Choose the start square')).toBeInTheDocument()
    expect(onMove).not.toHaveBeenCalled()
  })

  it('disables move entry while keeping the position labelled', () => {
    render(
      <InteractiveChessBoard
        fen={fen}
        orientation="white"
        label="Offline training position"
        disabled
        solution="e1e2"
        onMove={vi.fn()}
      />,
    )

    expect(screen.getByRole('group', { name: 'Offline training position' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'e1, empty' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'e2, white king' })).toBeDisabled()
    expect(screen.queryByTestId('best-move-arrow')).not.toBeInTheDocument()
  })
})
