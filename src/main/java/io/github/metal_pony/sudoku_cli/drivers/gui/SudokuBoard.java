package io.github.metal_pony.sudoku_cli.drivers.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.JComponent;
import javax.swing.JPanel;

import io.github.metal_pony.sudoku.Sudoku;

public class SudokuBoard extends JPanel implements ComponentListener {

	private static Color[] COLORS = new Color[] {
		Color.WHITE,
		Color.RED,
		new Color(255, 165, 0),
		Color.YELLOW,
		Color.GREEN,
		Color.CYAN,
		Color.BLUE,

		//new Color(0.3f, 0f, 0.51f), purple
		new Color(0f, 0.5f, 0f),

		new Color(0.8f, 0f, 0.8f),
		new Color(1f, 0.75f, 0.76f),
	};

	protected float fillRatio;

	protected Sudoku sudoku;
	protected Font font;

	protected int borderSize = 2;

	protected SudokuCell[] cells;

	protected static class SudokuCell extends JComponent implements MouseListener, KeyListener, MouseWheelListener {

		protected static SudokuCell selectedCell = null;
		protected static SudokuCell highlightedCell = null;

		protected static int size;
		protected static Font font;

		protected int digit;

		public SudokuCell(int digit) {
			this.digit = digit;

			this.addMouseListener(this);
			this.setFocusable(true);
			this.addKeyListener(this);
			this.addMouseWheelListener(this);
		}

		@Override protected void paintComponent(Graphics g) {
			super.paintComponent(g);

			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
			if (digit > 0) {
				g2.setColor(COLORS[digit]);
				g2.fill3DRect(0, 0, size, size, true);
				g2.setColor(Color.BLACK);
				g2.setFont(font);
				g2.drawString(Integer.toString(digit), size/4, size - size/8);
			}

			g2.setColor(Color.BLACK);
			if (highlightedCell == this || selectedCell == this) {
				int stroke = 3;
				g2.setStroke(new BasicStroke(stroke));
				g2.drawRect(stroke/2, stroke/2, size - stroke + 1, size - stroke + 1);
			} else {
				g2.drawRect(0, 0, size, size);
			}
		}

		@Override public void mousePressed(MouseEvent e) {
			SudokuCell prev = selectedCell;
			selectedCell = this;
			if (prev != null) {
				prev.repaint();
			}
			if (e.getButton() == MouseEvent.BUTTON3) {
				selectedCell = null;
			}
			repaint();
		}
		@Override public void mouseReleased(MouseEvent e) {}
		@Override public void mouseEntered(MouseEvent e) {
			SudokuCell prev = highlightedCell;
			highlightedCell = this;
			if (prev != null) {
				prev.repaint();
			}
			repaint();
			this.requestFocusInWindow();
		}
		@Override public void mouseExited(MouseEvent e) {
			SudokuCell prev = highlightedCell;
			highlightedCell = null;
			if (prev != null) {
				prev.repaint();
			}
			repaint();
		}
		@Override public void mouseClicked(MouseEvent e) {}
		@Override public void keyTyped(KeyEvent e) {}
		@Override public void keyPressed(KeyEvent e) {
			System.out.println("key event");
			System.out.println(this);
			System.out.println(selectedCell);
			System.out.println(highlightedCell);
			if (selectedCell == this || highlightedCell == this) {
				System.out.println("on selected/highlighted cell");
				System.out.println("e.getKeyCode() = " + e.getKeyCode());

				boolean numberKey = e.getKeyCode() >= KeyEvent.VK_1 && e.getKeyCode() <= KeyEvent.VK_9;
				boolean numpadKey = e.getKeyCode() >= KeyEvent.VK_NUMPAD1 && e.getKeyCode() <= KeyEvent.VK_NUMPAD9;

				if (numberKey) {
					System.out.println("setting digit to " + (e.getKeyCode() - KeyEvent.VK_1 + 1));
					digit = e.getKeyCode() - KeyEvent.VK_1 + 1;

				} else if (numpadKey) {
					System.out.println("setting digit to " + (e.getKeyCode() - KeyEvent.VK_NUMPAD1 + 1));
					digit = e.getKeyCode() - KeyEvent.VK_NUMPAD1 + 1;

				} else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
					digit = 0;
					System.out.println("erasing digit");
				}
				repaint();
			}
		}
		@Override public void keyReleased(KeyEvent e) {}
		@Override public void mouseWheelMoved(MouseWheelEvent e) {}
	}

	public SudokuBoard(Sudoku sudoku) {
		this.fillRatio = 0.8f;
		this.sudoku = sudoku;
		this.cells = new SudokuCell[Sudoku.SPACES];

		font = new Font("Consolas", Font.PLAIN, 24);
		SudokuCell.font = font.deriveFont(24f);

		setBackground(Color.white);

		addMouseListener(new MouseListener() {
			@Override public void mouseClicked(MouseEvent e) {}
			@Override public void mousePressed(MouseEvent e) {
				/*
				if (e.getButton() == MouseEvent.BUTTON1) {

					int w = getWidth();
					int h = getHeight();
					int cellSize = (int) (Math.min(w, h) * fillRatio/9);
					int xOffset = (w - cellSize*9)/2 - 2*borderSize;
					int yOffset = (h - cellSize*9)/2 - 2*borderSize;

					int x = e.getX() - xOffset;
					x -= (x/cellSize) * borderSize;
					int y = e.getY() - yOffset;

					if (	x > 0
							&& x < borderSize*((x/cellSize/3) + 1) + 9*cellSize
							&& y > 0
							&& y < borderSize*((y/cellSize/3) + 1) + 9*cellSize)
					{
						//int row = y / cellSize;
						//int col = x / cellSize;
						//selectedSquare = row * 9 + col;
					} else {
						//selectedSquare = -1;
					}

					repaint();
				} else {
					//selectedSquare = -1;
				}
				*/
			}
			@Override public void mouseReleased(MouseEvent e) { }
			@Override public void mouseEntered(MouseEvent e) { }
			@Override public void mouseExited(MouseEvent e) { }
		});

		addMouseMotionListener(new MouseMotionListener() {
			@Override public void mouseDragged(MouseEvent e) {}
			@Override public void mouseMoved(MouseEvent e) {
				/*
				Dimension size = getSize();
				int d = (int) (Math.min(size.width, size.height) * 0.9 / 8);
				int xOffset = (size.width - d * 8) / 2;
				int yOffset = (size.height - d * 8) / 2;

				int x = e.getX() - xOffset;
				int y = e.getY() - yOffset;

				if (x > 0 && x < d * 8 && y > 0 && y < d * 8) {
					int row = y / d;
					int col = x / d;
					//highlightSquare = row * 8 + col;
				} else {
					//highlightSquare = -1;
				}
				*/
				repaint();
			}
		});

		addComponentListener(this);

		this.setLayout(null);
		for (int i = 0; i < Sudoku.SPACES; i++) {
			cells[i] = new SudokuCell(sudoku.getDigit(i));
			add(cells[i]);
		}

	}

	@Override protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		int w = this.getWidth();
		int h = this.getHeight();
		int cellSize = (int) (Math.min(w, h) * fillRatio / 9);
		int xOffset = (w - cellSize * 9) / 2 - 2 * borderSize;
		int yOffset = (h - cellSize * 9) / 2 - 2 * borderSize;

		SudokuCell.size = cellSize;

		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

		//paint all cells, borders/grid
		/*
		g2.setColor(Color.BLACK);
		g2.setFont(font.deriveFont((float) cellSize));
		for (int i = 0; i < Board.NUM_CELLS; i++) {
			int r = i / 9;
			int c = i % 9;
			g2.setColor(COLORS[board.getValue(i)]);
			g2.fill3DRect(
					xOffset + borderSize * ((c / 3) + 1) + c * cellSize,
					yOffset + borderSize * ((r / 3) + 1) + r * cellSize,
					cellSize, cellSize, true);
		}

		g2.setColor(Color.BLACK);
		for (int i = 0; i < Board.NUM_CELLS; i++) {
			int r = i / 9;
			int c = i % 9;

			//g2.drawRect(
			//			xOffset + borderSize * ((c / 3) + 1) + c * cellSize,
			//			yOffset + borderSize * ((r / 3) + 1) + r * cellSize,
			//			cellSize, cellSize);
			if (board.getValue(i) > 0) {
				g2.drawString(Integer.toString(board.getValue(i)),
						xOffset + borderSize * ((c / 3) + 1) + c * cellSize + cellSize/4,
						yOffset + borderSize * ((r / 3) + 1) + (r + 1) * cellSize - cellSize/8);
			}
		}
		*/

		//g2.setColor(Color.BLACK);
		Stroke stroke = g2.getStroke();
		g2.setStroke(new BasicStroke(borderSize));

		//draw separating lines and border
		g2.drawLine(
				xOffset + borderSize + cellSize*3 + borderSize/2, yOffset + borderSize,
				xOffset + borderSize + cellSize*3 + borderSize/2, yOffset + borderSize*3 + cellSize*9);
		g2.drawLine(
				xOffset + borderSize*2 + cellSize*6 + borderSize/2, yOffset + borderSize,
				xOffset + borderSize*2 + cellSize*6 + borderSize/2, yOffset + borderSize*3 + cellSize*9);
		g2.drawLine(
				xOffset + borderSize, yOffset + borderSize*2 + cellSize*6 + borderSize/2,
				xOffset + borderSize*3 + cellSize*9, yOffset + borderSize*2 + cellSize*6 + borderSize/2);
		g2.drawLine(
				xOffset + borderSize, yOffset + borderSize + cellSize*3 + borderSize/2,
				xOffset + borderSize*3 + cellSize*9, yOffset + borderSize + cellSize*3 + borderSize/2);
		g2.drawRect(
				xOffset + borderSize/2 + 1, yOffset + borderSize/2 + 1,
				cellSize*9 + borderSize*3 - 1, cellSize*9 + borderSize*3 - 1);

		g2.setStroke(stroke);

	}

	@Override public void componentResized(ComponentEvent e) {
		int w = this.getWidth();
		int h = this.getHeight();
		int cellSize = (int) (Math.min(w, h) * fillRatio / 9);
		int xOffset = (w - cellSize * 9) / 2 - 2 * borderSize;
		int yOffset = (h - cellSize * 9) / 2 - 2 * borderSize;

		SudokuCell.size = cellSize;
		SudokuCell.font = font.deriveFont((float) cellSize);

		//POSITION ALL CELLS
		for (int i = 0; i < Sudoku.SPACES; i++) {
			int r = i / 9;
			int c = i % 9;
			cells[i].setBounds(
					xOffset + borderSize * ((c / 3) + 1) + c * cellSize,
					yOffset + borderSize * ((r / 3) + 1) + r * cellSize,
					cellSize, cellSize);
		}

	}

	@Override public void componentMoved(ComponentEvent e) {}
	@Override public void componentShown(ComponentEvent e) {}
	@Override public void componentHidden(ComponentEvent e) {}

}
