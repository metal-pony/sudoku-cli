package io.github.metal_pony.sudoku.drivers;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

import io.github.metal_pony.sudoku.Sudoku;
import io.github.metal_pony.sudoku.drivers.Main.ArgsMap;

/**
 * Generates a set of normalized initial bands (the top 3 rows of a sudoku board).
 *
 * CPU intensive in that it first generates a large set of all top band solutions,
 * then systematically eliminates symmetric duplicates.
 *
 * This generation is not perfect in that the set can definitely be reduced further
 * by checking for unavoidable sets.
 *
 * As of writing, the uber set generated is well over 2 million, and reduces down to 416.
 */
public class GenerateInitialBands {
    static class Node2 {
        Sudoku sudoku;
        int cellIndex = -1;
        int[] candidates = null;
        int candidateIndex = -1;
        public Node2(Sudoku sudoku) {
            this.sudoku = sudoku;
            sudoku.reduce();
            cellIndex = sudoku.pickEmptyCell(0, 27);
            if (cellIndex != -1) {
                candidates = sudoku.getCellCandidates(cellIndex);
                candidateIndex = 0;
            }
        }
        public Node2 next() {
            if (candidateIndex < 0 || candidates == null || candidateIndex >= candidates.length) return null;
            Sudoku sudokuCopy = new Sudoku(sudoku);
            int candidate = candidates[candidateIndex];
            sudokuCopy.setDigit(cellIndex, candidate);
            candidateIndex++;
            return new Node2(sudokuCopy);
        }
    }

    public static void generateInitialBands() {
        ArgsMap args = Main.args;
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long startTimeNs = bean.getCurrentThreadCpuTime();

        Set<String> fullBandSet = new HashSet<>();
        final int N = Sudoku.DIGITS * 3;

        if (args.isVerbose()) {
            System.out.println("-- Generating large initial bands set... --");
        }

        long timeNs = Main.timeCpuExecution(() -> {
            Stack<Node2> stack = new Stack<>();
            stack.push(new Node2(new Sudoku("123456789--------")));

            while (!stack.isEmpty()) {
                Node2 top = stack.peek();
                Node2 next = top.next();
                if (next == null) {
                    boolean hasEmptyInBand = top.sudoku.pickEmptyCell(0, N) >= 0;
                    if (!hasEmptyInBand) {
                        String bandStr = top.sudoku.toString().substring(0, N);
                        if (fullBandSet.add(bandStr)) {
                            if (args.isVerbose()) System.out.println(bandStr);
                        }
                    }
                    stack.pop();
                } else {
                    stack.push(next);
                }
            }
        });

        if (args.isVerbose()) {
            System.out.printf(
                " -- Done (%s ms). Generated %d initial bands. --\n",
                TimeUnit.NANOSECONDS.toMillis(timeNs),
                fullBandSet.size()
            );
            System.out.println("-- Reducing bands... --");
        }

        Set<String> reducedBandSet = reduceFullBandSet(fullBandSet, args.isVerbose());

        if (args.isVerbose()) {
            System.out.printf("-- REDUCED BANDS (%d) --\n", reducedBandSet.size());
        }
        reducedBandSet.forEach(System.out::println);
        long endTimeNs = bean.getCurrentThreadCpuTime();
        if (args.isVerbose()) {
            System.out.printf("-- DONE. Total time: %d ms. --\n", TimeUnit.NANOSECONDS.toMillis(endTimeNs - startTimeNs));
        }
    }

    private static Set<String> reduceFullBandSet(Set<String> fullBandSet, boolean isVerbose) {
        // TODO Reduce fullBandSet by discovering and removing transforms
        // For each BAND:
        //  new queue, new hashset<string> to track seen elements, add BAND
        //  while queue not empty:
        //    b = poll
        //    // always normalize after transform, before adding to queue
        //    add unseen block permutations to queue,
        //    add unseen row permutations to queue,
        //    add unseen column permutations to queue,
        //    band -> config -> search for UAs(level 2? 3?) -> when found, if (bandMask & ua) == ua -> if unseen, add to queue

        HashSet<String> reducedBands = new HashSet<>();
        final int N = Sudoku.DIGITS * 3;

        long timeNs = Main.timeCpuExecution(() -> {
            List<String> allBands = new ArrayList<>(fullBandSet);

            while (!allBands.isEmpty()) {
                String band = allBands.remove(allBands.size() - 1);
                // String bandPuzzleStr = band + "0".repeat(Sudoku.SPACES - band.length());
                // Sudoku bandPuzzle = new Sudoku(bandPuzzleStr);
                HashSet<String> seen = new HashSet<>();
                Queue<String> q = new LinkedList<>();
                seen.add(band);
                q.offer(band);
                reducedBands.add(band);

                // TODO NOT FEASIBLE TO COUNT SOLUTIONS
                // int rootCount = countSolutions(new Sudoku(band + "0".repeat(Sudoku.SPACES - band.length())));
                // if (verbose) {
                //   System.out.printf("Transforming band %s, all transforms should have %d solutions:\n", band, rootCount);
                // }

                while (!q.isEmpty()) {
                    String bStr = q.poll() + "0".repeat(Sudoku.SPACES - band.length());

                    // Transforms
                    Sudoku[] transforms = new Sudoku[] {
                        new Sudoku(bStr).swapStacks(1, 2),
                        new Sudoku(bStr).swapStacks(0, 1),
                        new Sudoku(bStr).swapStacks(0, 1).swapStacks(1, 2),
                        new Sudoku(bStr).swapStacks(0, 2).swapStacks(1, 2),
                        new Sudoku(bStr).swapStacks(0, 2),

                        new Sudoku(bStr).swapBandRows(0, 1, 2),
                        new Sudoku(bStr).swapBandRows(0, 0, 1),
                        new Sudoku(bStr).swapBandRows(0, 0, 1).swapBandRows(0, 1, 2),
                        new Sudoku(bStr).swapBandRows(0, 0, 2).swapBandRows(0, 1, 2),
                        new Sudoku(bStr).swapBandRows(0, 0, 2),

                        new Sudoku(bStr).swapStackCols(0, 1, 2),
                        new Sudoku(bStr).swapStackCols(0, 0, 1),
                        new Sudoku(bStr).swapStackCols(0, 0, 1).swapStackCols(0, 1, 2),
                        new Sudoku(bStr).swapStackCols(0, 0, 2).swapStackCols(0, 1, 2),
                        new Sudoku(bStr).swapStackCols(0, 0, 2),

                        new Sudoku(bStr).swapStackCols(1, 1, 2),
                        new Sudoku(bStr).swapStackCols(1, 0, 1),
                        new Sudoku(bStr).swapStackCols(1, 0, 1).swapStackCols(1, 1, 2),
                        new Sudoku(bStr).swapStackCols(1, 0, 2).swapStackCols(1, 1, 2),
                        new Sudoku(bStr).swapStackCols(1, 0, 2),

                        new Sudoku(bStr).swapStackCols(2, 1, 2),
                        new Sudoku(bStr).swapStackCols(2, 0, 1),
                        new Sudoku(bStr).swapStackCols(2, 0, 1).swapStackCols(2, 1, 2),
                        new Sudoku(bStr).swapStackCols(2, 0, 2).swapStackCols(2, 1, 2),
                        new Sudoku(bStr).swapStackCols(2, 0, 2)
                    };

                    for (Sudoku t : transforms) {
                        String tStr = t.normalize().toString().substring(0, N);
                        if (!seen.contains(tStr)) {
                            seen.add(tStr);
                            q.offer(tStr);

                            // TODO NOT FEASIBLE TO COUNT SOLUTIONS
                            // int count = countSolutions(new Sudoku(tStr));
                            // if (argsMap.isVerbose()) {
                            //   System.out.printf("%s [%d] %s\n", (rootCount == count) ? "  " : "🚨", count, tStr);
                            // }
                        }
                    }

                    // TODO Additional symmetries can be found by locating UAs within the band

                    // AtomicReference<Sudoku> atomicConfig = new AtomicReference<>();
                    // bandPuzzle.searchForSolutions(solution -> {
                    //   atomicConfig.set(solution);
                    //   return false;
                    // });
                    // Sudoku c = atomicConfig.get();
                    // SudokuSieve sieve = new SudokuSieve(c.getBoard());
                    // BigInteger bandMask = new BigInteger("1".repeat(N) + "0".repeat(Sudoku.SPACES - N), 2);
                    // for (int r = Sudoku.DIGIT_COMBOS_MAP[2].length - 1; r >= 0; r--) {
                    //   BigInteger pMask = c.maskForDigits(Sudoku.DIGIT_COMBOS_MAP[2][r]);
                    //   sieve.addFromFilter(pMask, (solution) -> {
                    //     // TODO item may need to be inverted
                    //     BigInteger item = c.diff2(solution);
                    //     if (item.equals(item.and(bandMask))) {
                    //       String tStr = solution.normalize().toString().substring(0, N);
                    //       if (!seen.contains(tStr)) {
                    //         seen.add(tStr);
                    //         q.offer(tStr);
                    //       }
                    //     }
                    //   });
                    // }
                }

                int sizeBefore = allBands.size();
                allBands.removeAll(seen);
                int sizeAfter = allBands.size();
                if (isVerbose) {
                    System.out.printf("Removed %d symmetrical bands, (%d remaining).\n", sizeBefore - sizeAfter, allBands.size());
                }
            }
        });

        if (isVerbose) {
            System.out.printf(
                "-- Done (%d ms). Removed %d symmetrical duplicate bands. --\n",
                TimeUnit.NANOSECONDS.toMillis(timeNs),
                fullBandSet.size() - reducedBands.size()
            );
        }

        return reducedBands;
    }
}
