import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ChessBoard, InteractiveChessBoard } from './ChessBoard'

const fen = '4k3/8/8/8/8/8/4r3/4K3 w - - 0 1'

describe('InteractiveChessBoard', () => {
  it('keeps the existing review board read-only', () => {
    render(<ChessBoard fen={fen} orientation="white" label="Review position" highlight={null} arrow={null} />)

    expect(screen.getByRole('img', { name: 'Review position' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('submits the same UCI move by square selection and drag', () => {
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

    const king = screen.getByRole('button', { name: 'e1, white king' })
    const rook = screen.getByRole('button', { name: 'e2, black rook' })
    fireEvent.click(king)
    expect(king).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(rook)
    expect(onMove).toHaveBeenLastCalledWith('e1e2')

    rerender(
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
    expect(screen.getByRole('button', { name: 'e1, white king' })).toBeDisabled()
    expect(screen.getByTestId('best-move-arrow')).toBeInTheDocument()
  })
})
