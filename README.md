# Sudoku-cli

Commandline interface for generating, solving sudokus.

## Requirements

Java JDK >= 21 for various syntax features.

GraalVM 21+ is required if building a native executable, which in turn has its own prerequisites of C compilation tools. See https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites.

## Building a JAR

> ./mvnw clean package

The JAR file will be built as `target/sudoku-cli-1.jar`.

## Building a native executable

Have GraalVM 21+ installed as your Java version. Then,
> ./mvnw clean package -Pnative

The executable will be built as `target/sudoku`.

## Usage

### Generate puzzles

```
Usage: sudoku generatePuzzles [-v] [--pretty] [-m=MASK | [[-c=<clues>] [-p]
                              [-s=SOLUTION]]] [AMOUNT]
Generate Sudoku puzzles.
      [AMOUNT]              Number of puzzles to generate.
  -c, --clues=<clues>       Number of clues on generated puzzles.
  -m, --mask=MASK           Use specific cells.
  -p, --palindrome          Generate with palindrome symmetry.
      --pretty              Format output to use multiple lines.
  -s, --solution=SOLUTION   Use a specific solution.
  -v, --v, --verbose        Show detailed output.
```

```shell
# Basic generations (default 27 clues).
> sudoku puzzle 5
.....8..98...16.7.94......8.92..186.46..........56.....3...958.28.3.4..6.1..8.423
..43...6....465..7.5.......93........2.1..43...5.396........17.4..5..3.8.9.81....
.7..8..4.4.2....7.....1.....59.7.4.6......91.8.1.9..5.3..62...8.8.9..1.......8..3
.52.....4..12..5.6..3..5...6........1...7..5..3....74..265..9.798...6.2....93....
..39.2........32.1.4....98..965..1....8....2..7...1..9..7.6..4..3..9...6.2..7.5..

# Specify a solution, number of clues, or palindrome symmetry.
> sudoku puzzle --solution 315429768974863251268175439682794315193582674457631892829317546536948127741256983 \
    --palindrome \
    --clues 24 \
    --pretty
3     |   2   |
  7 4 |       | 2
      | 1   5 |     9
------+-------+------
    2 |   9   |     5
  9   |       |   7
4     |   3   | 8
------+-------+------
8     | 3   7 |
    6 |       | 1 2
      |   5   |     3

# Generate a puzzle with specific cells.
> sudoku puzzle -m 1.......1..11.11...1..1..1.1...1...11.......1.1.....1...1...1.....1.1...1...1...1 \
    --pretty
4     |       |     5
    1 | 4   3 | 9
  9   |   8   |   6
------+-------+------
6     |   1   |     8
9     |       |     2
  2   |       |   4
------+-------+------
    7 |       | 5
      | 6   2 |
3     |   4   |     6
```
