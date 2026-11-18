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
