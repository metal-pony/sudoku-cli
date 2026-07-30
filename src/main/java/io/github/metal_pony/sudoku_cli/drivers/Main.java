package io.github.metal_pony.sudoku_cli.drivers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.github.metal_pony.sudoku.PuzzleEntry;
import io.github.metal_pony.sudoku.SieveSearcher;
import io.github.metal_pony.sudoku.Sudoku;
import io.github.metal_pony.sudoku.SudokuMask;
import io.github.metal_pony.sudoku.SudokuSieve;
import io.github.metal_pony.sudoku.util.Counting;

public class Main {
  private static final String DEFAULT_COMMAND = "generateSolutions";

  private static final List<String> lines = new ArrayList<>();
  static final ArgsMap args = new ArgsMap();
  private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();

  private static void out(Object x) { System.out.println(x); }
  private static void outf(String format, Object...args) { System.out.printf(format, args); }
  private static void verboseOut(Object x) {
    if (args.isVerbose()) System.out.println(x);
  }
  private static void verboseOutf(String format, Object...args) {
    if (Main.args.isVerbose()) System.out.printf(format, args);
  }
  private static long timeMs() { return System.currentTimeMillis(); }

  private static void sleep(long timeMs) {
    try {
      Thread.sleep(timeMs);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private static void readAllLines(InputStream inStream, List<String> lines) {
    try (
      BufferedReader reader = new BufferedReader(new InputStreamReader(inStream));
    ) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line.trim());
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private static void repeatThreadedAndBlock(Runnable runnable, int times, int threads) {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();
    for (int n = 0; n < times; n++) pool.submit(runnable);
    pool.shutdown();
    try {
      pool.awaitTermination(1L, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      pool.close();
      // out("thread pool closed");
    }
  }

  private static void runBatchAndBlock(List<Runnable> batch, int threads) {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();
    for (Runnable work : batch) pool.submit(work);
    pool.shutdown();
    try {
      pool.awaitTermination(1L, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      pool.close();
      // out("thread pool closed");
    }
  }

  public static final class ArgsMap extends HashMap<String,String> {
    private static final String VERBOSE_ARG1 = "verbose";
    private static final String VERBOSE_ARG2 = "v";
    private static final Set<String> ALGOS = new HashSet<>() {{
      add("dc2"); add("dc3"); add("dc4");
      add("fp2"); add("fp3"); add("fp4");
    }};

    public void parseCommandLineArgs(String[] args, int firstArgIndex) {
      if (args != null && args.length > firstArgIndex) {
        String lastArgKey = null;
        for (int i = firstArgIndex; i < args.length; i++) {
          String arg = args[i];

          // Args can be in the form `--key value`,
          // or (for things like boolean flags)  plainly `--key`.

          // If key -> add it to the map with an empty value.
          // Fails if the key contains non-alphabet chars.
          // Else (is value) -> pair it with the last key seen.
          // Fails if there has not been a key yet.
          if (arg.startsWith("--")) {
            lastArgKey = arg.substring(2);
            if (!lastArgKey.matches("[a-zA-Z]+")) {
              throw new IllegalArgumentException("Invalid argument format: " + String.join(" ", args));
            }
            put(lastArgKey, null);
          } else {
            if (lastArgKey == null || get(lastArgKey) != null) {
              throw new IllegalArgumentException("Invalid argument format: " + String.join(" ", args));
            }
            put(lastArgKey, arg);
          }
        }
      }

      // Check and cache whether verbose mode is set.
      isVerbose = (
        containsKey(VERBOSE_ARG1) ||
        containsKey(VERBOSE_ARG2)
      );

      threads = parseThreadsArgOrThrow(MAX_THREADS);
      normalize = containsKey("normalize");
      amount = containsKey("amount") ? Integer.parseInt(get("amount")) : -1;
      clues = containsKey("clues") ? Integer.parseInt(get("clues")) : -1;
      level = containsKey("level") ? Integer.parseInt(get("level")) : -1;

      algo = get("algo");
      if (algo != null) {
        algo = algo.replaceAll("\\W", "");
        if (algo.length() > 16) {
          throw new IllegalArgumentException("Malformed --algo");
        }
        if (!ALGOS.contains(algo)) {
          throw new IllegalArgumentException(String.format("Algorithm '%s' not recognized.", algo));
        }
      }
    }

    private boolean isVerbose = false;
    private boolean normalize = false;
    private int threads = -1;
    private int amount = -1;
    private int clues = -1;
    private int level = -1;
    private String algo = null;

    public boolean isVerbose() { return isVerbose; }
    public boolean normalize() { return normalize; }
    public int threads() { return threads; }
    public int amount() { return amount; }
    public int amountOrDefault(int defaultAmount) { return amount == -1 ? defaultAmount : amount; }
    public int clues() { return clues; }
    public int cluesOrDefault(int defaultClues) { return clues == -1 ? defaultClues : clues; }
    public int level() { return level; }
    public int levelOrDefault(int defaultLevel) { return level == -1 ? defaultLevel : level; }
    public String algo() { return algo; }
    public String algoOrDefault(String defaultAlgo) { return (algo == null) ? defaultAlgo : algo; }

    private int parseThreadsArgOrThrow(int maxThreads) {
      int threads = 1;

      if (containsKey("threads")) {
        String threadsStr = get("threads");
        if (threadsStr == null) {
          threads = maxThreads;
        } else {
          if ("max".equalsIgnoreCase(threadsStr)) {
            threads = maxThreads;
          } else {
            try {
              threads = Integer.parseInt(threadsStr);
            } catch (NumberFormatException ex) {
              System.err.println("ERROR: Bad argument '--threads'.");
              System.exit(1);
            }
          }
        }
      }

      if (threads < 1) {
        System.err.println("ERROR: Bad argument '--threads'.");
        System.exit(1);
      } else if (threads > maxThreads) {
        System.err.printf(
          "WARNING: --threads '%d' specified, but max specified was %d -- Consider using less threads.",
          threads,
          maxThreads
        );
      }

      return threads;
    }
  }

  private static final Map<String, Runnable> COMMANDS = new HashMap<>() {{
    put("help", Main::help);
    put("generateConfigs", Main::generateConfigs);
    put("generateSolutions", Main::generateConfigs);
    put("generatePuzzles", Main::generatePuzzles);
    put("generatePalis", Main::generatePalindromePuzzles);

    put("generatePaliMasks", Main::generatePaliMasks);
    put("paliSearch", Main::paliSearch);

    put("verify", Main::verify);
    put("solve", Main::solve);
    put("scramble", Main::scramble);
    put("csv", Main::csv);
    put("sieve", Main::createSieve);
    put("fingerprint", Main::fingerprint);
    put("fp", Main::fingerprint);
    put("sieveSearch", Main::sieveSearch);
    put("minSearch", Main::minSearch);

    // --puzzle %s --threads %d --timeout %d
    // For testing / experimentation
    put("benchy", Main::benchy);
    put("generateBands", GenerateInitialBands::generateInitialBands);
    put("adhoc", Main::adhoc);
    put("adhoc2", Main::adhoc2);
    put("wip1", Main::wip_1);
    put("wip2", Main::wip_2);
    put("wip3", Main::wip3);
    // put("check17", Main::check17);
  }};

  public static void main(String[] args) throws IOException, ClassNotFoundException {
    Main.args.parseCommandLineArgs(args, 1);

    String command = DEFAULT_COMMAND;
    if (args != null) {
      if (args.length >= 1) {
        command = args[0];
      }
    }

    verboseOutf("Command: %s\n", command);

    if (!COMMANDS.containsKey(command)) {
      System.err.println("Command not recognized.");
      System.exit(1);
    }

    // Check if there's any input to read from standard in.
    // If stdin is redirected/piped, System.console() will be null -> read it.
    // if (System.console() == null) {
    //   readAllLines(System.in, lines);
    // }
    // Avoid blocking when stdout is piped (e.g. `| tee ...`).
    // System.console() can be null even if stdin is a terminal, so only
    // read stdin if there is data immediately available.
    BufferedReader stdinProbe = new BufferedReader(new InputStreamReader(System.in));
    try {
      if (stdinProbe.ready()) {
        readAllLines(System.in, lines);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    COMMANDS.get(command).run();
  }

  private static void help() {
    out(
      """
      --- COMMANDS ---

      help                    Display commands and usage.

      generateSolutions       Generate sudoku solution(s).
        --amount {n >= 1}     Specify an amount to generate.
        --normalize           Arrange digits such that the top row of the board
                              reads 1 through 9 sequentially.
        --threads             Use all available system threads.
        --threads {n >= 1}    Use a given number of system threads.
                              Not recommended to use more than the system maximum.
        --pretty              Output grids in a more readable form.

      generatePuzzles         Generate sudoku puzzle(s).
        --amount {n >= 1}     Specify an amount to generate.
        --normalize           Arrange digits such that the top row of the solution
                              reads 1 through 9 sequentially.
        --threads             Use all available system threads.
        --threads {n >= 1}    Use a given number of system threads.
                              Not recommended to use more than the system maximum.
        --clues {17 - 81}     Generate a puzzle with a given number of clues.
                              Less than 20 is not recommended due to the
                              processing power required.
                              Default 31.
        --difficulty {1 - 3}  Specify the difficulty of the generate puzzle.
          1: Easy             Solvable by finding naked and hidden singles.
          2: Moderate         Solvable by finding {Not yet defined}
          3: Hard             Solvable with more advanced techniques than above.
                              Generation may hang or fail if the number of clues
                              is high, e.g., --clues 80 --difficulty 3 ; since there
                              aren't enough empty spaces on the grid to form a
                              difficult puzzle.
        --solution            Generate a random solution to use for the puzzle(s).
        --solution {str}      Use the given solution for the puzzle(s).
        --pretty              Output puzzles in a more readable form.

      solve                   Find solution(s) for a given grid.
        --grid {str}          The grid to find solutions for.
        --all                 Output all solutions.
        --first               Output the first solution found.
        --amount {n >= 1}     Output the first (n) solutions found.
        --count               Output only the number of solutions.
        --threads             Use all available system threads.
        --threads {n >= 1}    Use a given number of system threads.
                              Not recommended to use more than the system maximum.
        --pretty              Output solutions in a more readable form.

      scramble                Jumbles the input grid or puzzle randomly.
        --grid {str}          The grid to scramble.

      csv                     Transforms input sudoku data into csv.
                              Input should be plaintext puzzles, solutions, or
                              puzzle/solutions csv records.
        --format {str}        The output format.
                              Components should be wrapped in curly braces.
                              Unknown components will be calculated, so be
                              mindful of the CPU usage of level 4 fingerprints.
                  COMPONENTS  {puzzle} from input.
                              {solution} from input, or calculated from puzzle.
                              {dc2} digit-combo fingerprint (level 2).
                              {dc3} digit-combo fingerprint (level 3).
                              {dc4} ...
                              {fp2} full-print fingerprint (level 2).
                              {fp3} ...
                              {fp4} ...
                  EXAMPLE     csv --format '{puzzle},{solution},{fp3}'
        --threads             Use all available system threads.
        --threads {n >= 1}    Use a given number of system threads.

      sieve                   Generate a set of unavoidable sets for a given grid.
        --algo                The strategy to use for seeding the sieve.
                              Usually digit-combos (dc), or full-print (fp),
                              from levels 2 to 4.
                      VALUES  "dc2", "dc3", "dc4", "fp2", "fp3", "fp4".
                              Default: fp3.

      fingerprint (alt: fp)   Generate a hash for a grid. The fingerprint will be
                              the same regardless of how the grid is transformed
                              via symmetry-preserving transformations. All grids
                              that are essentially similar are guaranteed to share
                              the same fingerprint.
        --algo                The strategy to use. Usually digit-combos (dc), or
                              full-print (fp), from levels 2 to 4.
                              "dc2", "dc3", "dc4", "fp2", "fp3", "fp4".
                              Default: fp3.
        --threads             Use all available system threads.
        --threads {n >= 1}    Use a given number of system threads.
                              Not recommended to use more than the system maximum.

      sieveSearch             A hitting-set search for finding lower-clue puzzles.
        --grid {str}          The grid to find puzzles for. Default: random.
        --level {2 - 4}       The level at which to seed the sieve. Default: 3.
        --maxClues {17 - 81}  Maximum number of puzzle clues. The search may discover
                              puzzles with less than this maximum. Default: no max.

      minSearch               A hitting-set search designed to find puzzles with
                              the lowest number of clues.
        --grid {str}          The grid to find minimum puzzles for. Default: random.
        --level {2 - 4}       The level at which to seed the sieve. Default: 3.
      """
    );
  }

  private static void generateConfigs() {
    final boolean normalize = args.normalize();
    final int threads = args.threads();
    final int amount = args.amountOrDefault(1);

    int realThreads = Math.min(threads, amount);
    List<Runnable> batch = new ArrayList<>(realThreads);
    int amountPerThread = amount / realThreads;
    int remainder = amount % realThreads;
    String lineSeparator = System.lineSeparator();

    for (int t = 0; t < realThreads; t++) {
      final int amountToGen = (t == realThreads - 1) ? (amountPerThread + remainder) : amountPerThread;
      final int bufCapacity = 1<<8;
      Sudoku config = new Sudoku();
      final int _t = t;
      batch.add(() -> {
        int bufSize = _t * (bufCapacity / realThreads);
        StringBuilder strb = new StringBuilder();
        for (int n = 0; n < amountToGen; n++) {
          config.genConfig();
          if (normalize) config.normalize();
          strb.append(config.toString());
          strb.append(lineSeparator);
          bufSize++;
          if (bufSize == bufCapacity) {
            System.out.print(strb.toString());
            bufSize = 0;
            strb.delete(0, strb.length());
          }
        }
        if (strb.length() > 0) {
          System.out.print(strb.toString());
        }
      });
    }

    long start = timeMs();
    // repeatThreadedAndBlock(genFunc, amount, threads);
    runBatchAndBlock(batch, realThreads);
    long end = timeMs();

    long ms = end - start;
    double secs = ms / 1000.0;
    double gridsPerSec = secs > 0.0 ? amount / secs : 0.0;

    verboseOutf(
      "Generated %d configs in %d ms (%.2f grids/second).\n",
      amount, ms, gridsPerSec
    );
  }

  // TODO Output as json
  // TODO Difficulty option
  private static void generatePuzzles() {
    final boolean normalize = args.normalize();
    final boolean prettyPrint = args.containsKey("pretty");
    final int threads = args.threads();
    final int amount = args.amountOrDefault(1);
    final int clues = args.cluesOrDefault(27);

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    verboseOutf("Using %d threads.\n", threads);

    if (lines.isEmpty()) {
      // Generate {amount} puzzles, each from random solutions.
      verboseOutf(
        "Generating %d %d-clue puzzles from random solutions.\n",
        amount, clues
      );

      Runnable generatePuzzle = () -> {
        Sudoku puzzle = Sudoku.generatePuzzle(clues);
        if (normalize) puzzle.normalize();
        out(prettyPrint ? puzzle.toMedString() : puzzle.toString());
        // TODO Handle timeout
        // if (puzzle == null) {
        //   // Timed out
        //   return;
        // } else {
        //   System.out.println(puzzle);
        // }
      };

      long start = timeMs();
      repeatThreadedAndBlock(generatePuzzle, amount, threads);
      long end = timeMs();
      verboseOutf("Done generating in %d ms.\n", end - start);
      return;
    }

    // ELSE: Multiple grids given as input.
    // Generate {amount} puzzles for each grid (given as the solutions).

    verboseOutf(
      "Generating %d %d-clue puzzles for each given solutions (%d).\n",
      amount, clues, lines.size()
    );

    for (String solutionStr : lines) {
      try {
        final Sudoku solution = new Sudoku(solutionStr);
        final SudokuSieve sieve = new SudokuSieve(solution);
        Runnable generatePuzzle = () -> {
          Sudoku puzzle = Sudoku.generatePuzzle(solution, clues, sieve, 0, 60*1000L);
          if (normalize) puzzle.normalize();
          out(prettyPrint ? puzzle.toMedString() : puzzle.toString());
          // TODO Handle timeout
          // if (puzzle == null) {
          //   // Timed out
          //   return;
          // } else {
          //   System.out.println(puzzle);
          // }
        };

        verboseOutf("Solution:\n%s\nPuzzles:\n", prettyPrint ? solution.toMedString() : solution.toString());
        long start = timeMs();
        repeatThreadedAndBlock(generatePuzzle, amount, threads);
        long end = timeMs();
        verboseOutf("-- %d ms --\n", end - start);
      } catch (Exception ex) {
        System.err.println("ERROR: Specified grid is malformed.");
        System.exit(1);
      }
    }
  }

  /**
   * Generates a given amount of palindrome puzzle masks.
   */
  private static void generatePaliMasks() {
    final int threads = args.threads();
    final int amount = args.amountOrDefault(1);
    final int clues = args.cluesOrDefault(27);

    verboseOutf("Using %d threads.\n", threads);

    if (lines.isEmpty()) {
      // Generate {amount} puzzles, each from random solutions.
      verboseOutf(
        "Generating %d %d-clue puzzles from random solutions.\n",
        amount, clues
      );

      Runnable generatePuzzle = () -> {
        SudokuMask mask = new SudokuMask();
        mask.randomPalindrome(clues);
        out(mask.toStringDots());
      };

      long start = timeMs();
      repeatThreadedAndBlock(generatePuzzle, amount, threads);
      long end = timeMs();
      verboseOutf("Done generating in %d ms.\n", end - start);
      return;
    }
  }

  private static void generatePalindromePuzzles() {
    final boolean normalize = args.normalize();
    final boolean prettyPrint = args.containsKey("pretty");
    final int threads = args.threads();
    final int amount = args.amountOrDefault(1);
    final int clues = args.cluesOrDefault(27);

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    verboseOutf("Using %d threads.\n", threads);

    if (lines.isEmpty()) {
      // Generate {amount} puzzles, each from random solutions.
      verboseOutf(
        "Generating %d %d-clue puzzles from random solutions.\n",
        amount, clues
      );

      Runnable generatePuzzle = () -> {
        Sudoku puzzle = Sudoku.generatePalindromePuzzle(clues);
        if (normalize) puzzle.normalize();

        if (args.isVerbose()) {
          if (prettyPrint) {
            outf("[%d] %s\n%s\n\n", puzzle.numClues(), puzzle.toString(), puzzle.toMedString());
          } else {
            outf("[%d] %s\n", puzzle.numClues(), puzzle.toString());
          }
        } else {
          out(prettyPrint ? puzzle.toMedString() : puzzle.toString());
        }
        // TODO Handle timeout
        // if (puzzle == null) {
        //   // Timed out
        //   return;
        // } else {
        //   System.out.println(puzzle);
        // }
      };

      long start = timeMs();
      repeatThreadedAndBlock(generatePuzzle, amount, threads);
      long end = timeMs();
      verboseOutf("Done generating in %d ms.\n", end - start);
      return;
    }

    // ELSE: Multiple grids given as input.
    // TODO Generate {amount} puzzles for each grid (given as the solutions).

    verboseOutf(
      "Generating %d %d-clue puzzles for each given solutions (%d).\n",
      amount, clues, lines.size()
    );

    // for (String solutionStr : lines) {
    //   try {
    //     final Sudoku solution = new Sudoku(solutionStr);
    //     final SudokuSieve sieve = new SudokuSieve(solution);
    //     Runnable generatePuzzle = () -> {
    //       Sudoku puzzle = Sudoku.generatePuzzle(solution, clues, sieve, 0, 60*1000L);
    //       if (normalize) puzzle.normalize();
    //       out(prettyPrint ? puzzle.toMedString() : puzzle.toString());
    //       // TODO Handle timeout
    //       // if (puzzle == null) {
    //       //   // Timed out
    //       //   return;
    //       // } else {
    //       //   System.out.println(puzzle);
    //       // }
    //     };

    //     verboseOutf("Solution:\n%s\nPuzzles:\n", prettyPrint ? solution.toMedString() : solution.toString());
    //     long start = timeMs();
    //     repeatThreadedAndBlock(generatePuzzle, amount, threads);
    //     long end = timeMs();
    //     verboseOutf("-- %d ms --\n", end - start);
    //   } catch (Exception ex) {
    //     System.err.println("ERROR: Specified grid is malformed.");
    //     System.exit(1);
    //   }
    // }
  }

  private static void paliSearch() {
    // final boolean normalize = args.normalize();
    // final boolean prettyPrint = args.containsKey("pretty");
    final int threads = args.threads();
    // final int amount = args.amountOrDefault(1);
    final int clues = args.cluesOrDefault(27);

    final long startTimeMs = System.currentTimeMillis();

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }
    if (lines.isEmpty()) {
      lines.add(Sudoku.generateConfig().toString());
    }

    SudokuMask m = new SudokuMask();
    // int clues = 24;
    int k = clues / 2;
    long nck = Counting.nChooseK(40, k).longValueExact();

    Sudoku solution = new Sudoku(lines.get(0));
    System.out.println("Searching for palindrome puzzles. Solution:");
    System.out.println(solution.toString());

    SudokuSieve sieve = new SudokuSieve(solution);
    verboseOut("Seeding sieve...");
    sieve.seedThreaded(sieve.fullPrintCombos(4));
    verboseOut(sieve.toString());
    final int sieveSizeAtStart = sieve.size();
    verboseOutf("Done. Sieve has %d items.\n", sieveSizeAtStart);
    verboseOut("Starting sequential search...");

    long sieveTests = 0L;
    long puzzleTests = 0L;
    long puzzles = 0L;
    int flag = 2;

    long r = 0L;
    // AtomicInteger solutionCount = new AtomicInteger();
    // AtomicBoolean addedToSieve = new AtomicBoolean();
    int solutionCount = 0;
    boolean addedToSieve = false;
    while (r < nck) {
      // Accelerate r until the mask satisfies the sieve
      do {
        m.palindrome(clues, r);
        sieveTests++;
        r++;
      } while (!sieve.doesMaskSatisfy(m) && r < nck);
      if (r >= nck) break;

      Sudoku p = solution.filter(m);
      // System.out.printf(
      //   "✅ %s [%d] {ss %d} {st %d} {pt %d}\n",
      //   p.toString(), r, sieve.size(), sieveTests, puzzleTests
      // );
      // solutionCount.set(0);
      // addedToSieve.set(false);

      // solutionCount = 0;
      // addedToSieve = false;
      // for (Sudoku s : p.solutions()) {
      //   SudokuMask diffMask = solution.diffMask(s);
      //   solutionCount++;
      //   if (sieve.add(diffMask)) {
      //     verboseOutf(" + " + solution.filterStr(diffMask) + " added to sieve");
      //     addedToSieve = true;
      //     break;
      //   }
      // }
      // flag = (!addedToSieve && solutionCount == 1) ? 1 : 2;

      flag = p.solutionsFlag();

      puzzleTests++;
      if (flag == 1) {
        // System.out.printf("⭐️ %s [%d] {r %d}\n", p.toString(), puzzles++, r);
        if (args.isVerbose()) {
          verboseOutf("[%d] %s {r %d}\n", puzzles, p.toString(), r);
        } else {
          System.out.println(p.toString());
        }
        puzzles++;
      }
    }

    final long endTimeMs = System.currentTimeMillis();

    verboseOutf(
      """
      Done in %d ms.
      Searched through %d palindrome masks.
      { sieveTests: %d, puzzleTests: %d, itemsAddedToSieve: %d, puzzlesFound: %d }
      """,
      endTimeMs - startTimeMs,
      nck,
      sieveTests, puzzleTests, sieve.size() - sieveSizeAtStart, puzzles
    );
  }

  private static void verify() {
    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR: No grid(s) given.");
      System.exit(1);
    }

    String[] flagStrs = new String[]{
      "❌ no solutions",
      "✅ valid",
      "🔸 many solutions",
    };

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        if (!Sudoku.isValidStr(gridStr)) {
          if (gridStr.length() > Sudoku.SPACES) {
            gridStr = gridStr.substring(0, Sudoku.SPACES) + "...(etc)";
          }
          outf("%s -> invalid grid (malformed)", gridStr);
          continue;
        }
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR: Specified grid is malformed.");
        System.exit(1);
      }

      int flag = grid.solutionsFlag();
      if (args.isVerbose()) {
        outf(
          "%s -> solutionsFlag: %d (%s)\n",
          grid.toString(),
          flag,
          flagStrs[flag]
        );
      } else {
        outf("%s, %s\n", grid.toString(), Boolean.toString(flag == 1));
      }
    }
  }

  private static void solve() {
    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR solve: No grid(s) given.");
      System.exit(1);
    }

    final int threads = args.threads();

    // Some of these options are not compatible together.
    // Precendence:
    // count -> all -> amount (default 1)
    boolean countSolutions = args.containsKey("count");
    boolean allSolutions = args.containsKey("all");
    final int amount = args.amountOrDefault(1);

    // If finding first solution (not all solutions, not counting, not up to certain amount)
    // then use one thread per solution search with this helper method.
    if (!allSolutions && !countSolutions && amount == 1) {
      long start = System.currentTimeMillis();
      batchFindFirstSolutions(lines, threads, args.isVerbose());
      long end = System.currentTimeMillis();
      verboseOutf("Found solutions for %d puzzles in %d ms.\n", lines.size(), end-start);
      return;
    }

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR: Specified grid is malformed.");
        System.exit(1);
      }

      if (countSolutions) {
        long count = grid.countSolutionsAsync(threads);
        if (args.isVerbose()) {
          outf("%s -> %d solutions\n", grid.toString(), count);
        } else {
          out(count);
        }
      } else if (allSolutions) {
        grid.searchForSolutionsAsync(solution -> out(solution.toString()), threads);
      } else {

        AtomicInteger solutionCount = new AtomicInteger();
        grid.searchForSolutions(solution -> {
          out(solution.toString());
          return solutionCount.incrementAndGet() < amount;
        });
      }
    }
  }

  private static void batchFindFirstSolutions(List<String> gridStrs, int threads, boolean isVerbose) {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();
    List<Future<String>> solutionFtrs = new ArrayList<>(lines.size());

    for (String gridStr : gridStrs) {
      try {
        Sudoku grid = new Sudoku(gridStr);
        solutionFtrs.add(pool.submit(() -> {
          Sudoku solution = grid.solution();
          return solution == null ? "No solution" : solution.toString();
        }));
      } catch (Exception ex) {
        System.err.println("ERROR solve: Specified grid is malformed.");
        System.exit(1);
      }
    }

    pool.shutdown();
    // Print solutions (in original order).
    for (int i = 0; i < solutionFtrs.size(); i++) {
      try {
        if (isVerbose) {
          outf("%s -> %s\n", gridStrs.get(i), solutionFtrs.get(i).get());
        } else {
          out(solutionFtrs.get(i).get());
        }
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }
    // Close up
    try {
      pool.awaitTermination(1L, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      pool.close();
    }
  }

  private static void scramble() {
    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR scramble: No grid(s) given.");
      System.exit(1);
    }

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR scramble: Specified grid is malformed.");
        System.exit(1);
      }

      out(grid.scramble().toString());
    }
  }

  // Output all sudoku17 data and fields (specified in 'format') as csv
  private static void csv() {
    int threads = args.threads();
    String format = args.get("format");

    // Input can be either
    // (1) puzzles,
    // (2) solutions, or
    // (3) csv containing (1) or (2) as first element

    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.MINUTES,
      new LinkedBlockingQueue<>()
    );

    long start = System.currentTimeMillis();

    for (PuzzleEntry s17Entry : PuzzleEntry.allSudoku17()) {
      pool.submit(() -> {
        System.out.println(s17Entry.toFormatted(format));
      });
    }

    pool.shutdown();
    try {
      pool.awaitTermination(1L, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    long end = System.currentTimeMillis();
    verboseOutf("Done in %d ms.", end - start);
  }

  private static int inBounds(int value, int min, int max) {
    return Math.max(min, Math.min(value, max));
  }

  private static <K,V> void defaultInMap(Map<K,V> map, K key, V defaultValue) {
    V value = map.get(key);
    if (value == null) {
      map.put(key, defaultValue);
    }
  }

  // TODO #67 Create general REPL tool
  // public static void repl() {
  //   Scanner scanner = new Scanner(System.in);
  //   System.out.println("Sudoku. \"help\" to list commands, \"exit\" or Ctrl+C to exit.");
  //   String line = scanner.nextLine().trim().toLowerCase();
  //   while (!line.equals("exit")) {
  //     switch (line) {
  //       case "help":
  //         System.out.println("""
  //         """);
  //         break;
  //       default:
  //         break;
  //     }
  //   }
  //   scanner.close();
  // }

  private static long timeCpuExecution(Runnable runnable, int n) {
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    long start = bean.getCurrentThreadCpuTime();
    for (int t = 0; t < n; t++) {
      runnable.run();
    }
    long end = bean.getCurrentThreadCpuTime();
    return TimeUnit.NANOSECONDS.toMillis(end - start);
  }

  static long timeCpuExecution(Runnable runnable) {
    return timeCpuExecution(runnable, 1);
  }

  /**
   * level [2,4] (default: 2)
   * grid? [str] (default: randomly generated)
   * threads? [1, #cores - 2] (default: 1 if omitted; MAX if only "--threads" given)
   */
  private static void createSieve() {
    final int threads = args.threads();
    final String algo = args.algoOrDefault("dc2");

    final boolean showBigInt = args.containsKey("bigint");

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR: No grid(s) given.");
      System.exit(1);
    }

    verboseOutf("Creating sieves for given grids, with algo %s, and %d threads.", algo, threads);

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR: Specified grid is malformed.");
        System.exit(1);
      }

      long startTime = System.currentTimeMillis();
      SudokuSieve sieve = new SudokuSieve(grid);
      List<SudokuMask> seedingMasks;
      switch (algo) {
        case "dc2": seedingMasks = sieve.digitCombos(2); break;
        case "dc3": seedingMasks = sieve.digitCombos(3); break;
        case "dc4": seedingMasks = sieve.digitCombos(4); break;
        case "fp2": seedingMasks = sieve.fullPrintCombos(2); break;
        case "fp3": seedingMasks = sieve.fullPrintCombos(3); break;
        case "fp4": seedingMasks = sieve.fullPrintCombos(4); break;
        default: throw new RuntimeException("Something went wrong seeding sieve.");
      }
      sieve.seedThreaded(seedingMasks, threads);
      long endTime = System.currentTimeMillis();
      out(grid.toString());
      if (showBigInt) {
        for (SudokuMask item : sieve.items(new ArrayList<>())) {
          String decimalStr = (new BigInteger(item.toString(), 2)).toString();
          System.out.printf("%30s %s\n", decimalStr, grid.filterStr(item));
        }
      } else {
        out(sieve.toString());
      }
      outf("Added %d items to sieve (%d ms).\n", sieve.size(), endTime - startTime);
    }
  }

  private static void fingerprint() {
    final int threads = args.threads();
    final String algo = args.algoOrDefault("fp3");

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR fingerprint: No grid(s) given.");
      System.exit(1);
    }

    if (threads > 1) verboseOutf("Using %d threads.\n", threads);

    if (lines.size() >= threads && threads > 1) {
      batchFingerprinting();
      return;
    }

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR fingerprint: Specified grid is malformed.");
        System.err.println("\"" + gridStr + "\"");
        System.exit(1);
      }

      long start = timeMs();
      String fp;
      switch (algo) {
        case "dc2": fp = grid.dc(2, threads); break;
        case "dc3": fp = grid.dc(3, threads); break;
        case "dc4": fp = grid.dc(4, threads); break;
        case "fp2": fp = grid.fp(2, threads); break;
        case "fp3": fp = grid.fp(3, threads); break;
        case "fp4": fp = grid.fp(4, threads); break;
        default: throw new RuntimeException("Something went wrong creating fingerprint.");
      }
      long sysTime = timeMs() - start;
      if (args.isVerbose()) {
        System.out.printf("%s\n%s: %s (%d ms)\n", grid.toString(), algo, fp, sysTime);
      } else {
        out(fp);
      }
    }
  }

  private static void batchFingerprinting() {
    final int threads = args.threads();
    final String algo = args.algoOrDefault("fp3");

    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();
    List<Future<String>> solutionFtrs = new ArrayList<>(lines.size());

    for (int i = 0; i < lines.size(); i++) {
      String gridStr = lines.get(i);
      final int _i = i;
      try {
        Sudoku grid = new Sudoku(gridStr);
        solutionFtrs.add(pool.submit(() -> {
          long start = timeMs();
          String fp;
          switch (algo) {
            case "dc2": fp = grid.dc2(); break;
            case "dc3": fp = grid.dc3(); break;
            case "dc4": fp = grid.dc4(); break;
            case "fp2": fp = grid.fp2(); break;
            case "fp3": fp = grid.fp3(); break;
            case "fp4": fp = grid.fp4(); break;
            default: throw new RuntimeException("Something went wrong creating fingerprint.");
          }
          long sysTime = timeMs() - start;
          if (args.isVerbose()) {
            return String.format("[%d] %s, %s: %s // (%d ms)", _i, grid.toString(), algo, fp, sysTime);
          } else {
            return fp;
          }
        }));
      } catch (Exception ex) {
        System.err.println("ERROR fingerprint: Specified grid is malformed.");
        System.exit(1);
      }
    }

    pool.shutdown();
    // Print solutions (in original order).
    for (int i = 0; i < solutionFtrs.size(); i++) {
      try {
        out(solutionFtrs.get(i).get());
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }
    // Close up
    try {
      pool.awaitTermination(1L, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      pool.close();
    }
  }

  /**
   * Checks whether the given puzzle, assumed to be a palindrome, is reducible. i.e., if two
   * complementing cells can be cleared while maintaining a single solution for the puzzle.
   * Palindrome puzzles are 'prime' if they are not reducible.
   *
   * @param p
   * @return
   */
  private static boolean isPrimePali(Sudoku p) {
    int flag = p.solutionsFlag();
    if (flag != 1) return false;

    Sudoku test = new Sudoku(p);

    // Quick failure case:
    // A digit exists in the center of the puzzle,
    // which can be removed without affecting solvability,
    // then the palindrome is not "prime".
    int prev1 = test.getDigit(40);
    if (prev1 > 0) {
      test.clearCell(40);
      flag = test.solutionsFlag();
      if (flag == 1) {
        // verboseOutf("❌ %s\n", test.toString());
        return false;
      }
      test.setDigit(40, prev1);
    }

    int prev2 = 0;
    for (int ci = 0; ci < Sudoku.SPACES / 2; ci++) {
      prev1 = test.getDigit(ci);
      if (prev1 > 0) {
        prev2 = test.getDigit(Sudoku.SPACES - ci - 1);
        test.clearCell(ci);
        test.clearCell(Sudoku.SPACES - ci - 1);
        flag = test.solutionsFlag();
        if (flag == 1) {
          // verboseOutf("❌ %s\n", test.toString());
          return false;
        }
        test.setDigit(ci, prev1);
        test.setDigit(Sudoku.SPACES - ci - 1, prev2);
      }
    }

    return true;
  }

  // Generates random palindrome mask (loops until mask satisfies prime sieve)
  // (Sudoku.searchForPuzzlesAsync) Searches for puzzles with the mask shape
  private static void wip_1() {
    final int clues = args.cluesOrDefault(24);
    if (clues < Sudoku.MIN_CLUES || clues > Sudoku.SPACES) {
      throw new IllegalArgumentException("clues out of range");
    }

    // Generate a random palindrome SudokuMask that satisfies the prime sieve.

    long paliGenTimeStart = System.currentTimeMillis();
    SudokuMask paliMask = new SudokuMask();
    final int k = clues / 2;
    final long nck = Counting.NChooseKLong(40, k);
    long r = 0L;
    do {
      r = ThreadLocalRandom.current().nextLong(nck);
      paliMask.palindrome(clues, r);
    } while (!SudokuMask.satisfiesPrimeSieve(paliMask));
    long paliGenTimeEnd = System.currentTimeMillis();
    System.out.printf(
      """
      Generated random palindrome mask.
      palindrome(clues = %d, r = %d)
      (%d ms)
      """,
      clues, r, (paliGenTimeEnd - paliGenTimeStart)
    );
    System.out.println(paliMask.toStringDots());
    System.out.println(paliMask.toMedString());

    System.out.println("Searching for valid puzzles...");
    long startTime = System.currentTimeMillis();
    AtomicLong count = new AtomicLong();
    Sudoku.searchForPuzzlesAsync(paliMask, (puzzle) -> {
      System.out.printf("[%d] %s\n", count.incrementAndGet(), puzzle.toString());
      return true;
    });
    long endTime = System.currentTimeMillis();
    System.out.printf(
      "Found %d puzzles of palindrome(clues = %d, r = %d). (%d ms)\n",
      count.get(), clues, r, (endTime - startTime)
    );

    // System.out.println(paliMask.toMedString());

    // Sudoku.searchForPuzzles(paliMask, (puzzle) -> {
    //   System.out.println(puzzle.toString());
    //   return true;
    // });
  }

  // Utility. Takes multiple grids and outputs a list of strings representing them
  // as a grid, each in MedString form with {numPerRow} per row.
  // Example: `sudokuLines(listOfSudoku, 4, "    ")`
  private static List<String> sudokuLines(List<Sudoku> sudokus, int numPerRow, String delim) {
    final int len = sudokus.size();
    List<String> lines = new ArrayList<>();
    if (len == 0) return lines;

    // with toMedString, 11
    final int linesPerSudoku = sudokus.get(0).toMedString().split("\n").length;

    String[][] sudokuStrs = new String[numPerRow][];
    for (int j = 0; j < numPerRow; j++) {
      sudokuStrs[j] = new String[linesPerSudoku];
    }

    for (int i = 0; i < len; i+=numPerRow) {
      for (int j = 0; j < numPerRow; j++) {
        Arrays.fill(sudokuStrs[j], "");
        if (i + j < len) {
          sudokuStrs[j] = sudokus.get(i + j).toMedString().split("\n");
        }
      }

      for (int line = 0; line < linesPerSudoku; line++) {
        String[] sudokuLines = new String[numPerRow];
        for (int j = 0; j < numPerRow; j++) {
          sudokuLines[j] = sudokuStrs[j][line];
        }

        // System.out.println(String.join(delim, sudokuLines).trim());
        lines.add(String.join(delim, sudokuLines).trim());
      }
      // System.out.println("\n");
      lines.add("");
    }
    return lines;
  }

  private static void wip_2() {
    List<PuzzleEntry> all17 = PuzzleEntry.allSudoku17();
    final int len = all17.size();
    String delim = "    ";
    final int sudokuPerLine = 4;

    // String[][] sudokuStrs = new String[sudokuPerLine][];
    // for (int j = 0; j < sudokuPerLine; j++) {
    //   sudokuStrs[j] = new String[11];
    // }

    // for (int i = 0; i < len; i+=sudokuPerLine) {
    //   for (int j = 0; j < sudokuPerLine; j++) {
    //     Arrays.fill(sudokuStrs[j], "");
    //     if (i + j < len) {
    //       sudokuStrs[j] = all17.get(i + j).puzzle().toMedString().split("\n");
    //     }
    //   }

    //   int lines = sudokuStrs[0].length;
    //   for (int line = 0; line < lines; line++) {
    //     String[] sudokuLines = new String[sudokuPerLine];
    //     for (int j = 0; j < sudokuPerLine; j++) {
    //       sudokuLines[j] = sudokuStrs[j][line];
    //     }

    //     System.out.println(String.join(delim, sudokuLines).trim());
    //   }
    //   System.out.println("\n");
    // }

    Map<Integer,List<Sudoku>> emptyRowsPuzzles = new HashMap<>();
    Map<Integer,List<Sudoku>> emptyColsPuzzles = new HashMap<>();
    Map<Integer,List<Sudoku>> emptyRegionsPuzzles = new HashMap<>();
    Map<Integer,List<Sudoku>> emptyAreasPuzzles = new HashMap<>();
    for (int i = 0; i < 9; i++) {
      emptyRowsPuzzles.put(i, new ArrayList<>());
      emptyColsPuzzles.put(i, new ArrayList<>());
      emptyRegionsPuzzles.put(i, new ArrayList<>());
      emptyAreasPuzzles.put(i, new ArrayList<>());
    }
    for (PuzzleEntry entry : all17) {
      Sudoku p = entry.puzzle();

      int emptyRows = p.numEmptyRows();
      int emptyCols = p.numEmptyCols();
      int emptyRegions = p.numEmptyRegions();
      int emptyAreas = emptyRows + emptyCols + emptyRegions;

      emptyRowsPuzzles.get(emptyRows).add(p);
      emptyColsPuzzles.get(emptyCols).add(p);
      emptyRegionsPuzzles.get(emptyRegions).add(p);
      emptyAreasPuzzles.get(emptyAreas).add(p);
    }

    System.out.println("EMPTY ROW PUZZLES:");
    for (int i = 0; i < 4; i++) System.out.printf("[%d]: %d\n", i, emptyRowsPuzzles.get(i).size());
    System.out.println("EMPTY COL PUZZLES:");
    for (int i = 0; i < 4; i++) System.out.printf("[%d]: %d\n", i, emptyColsPuzzles.get(i).size());
    System.out.println("EMPTY REGION PUZZLES:");
    for (int i = 0; i < 4; i++) System.out.printf("[%d]: %d\n", i, emptyRegionsPuzzles.get(i).size());
    System.out.println("EMPTY AREA PUZZLES:");
    for (int i = 0; i < 9; i++) System.out.printf("[%d]: %d\n", i, emptyAreasPuzzles.get(i).size());

    System.out.println("EMPTY ROWS (3) PUZZLES:");
    // emptyRowsPuzzles.get(3).forEach(p -> System.out.println(p.toMedString()));
    sudokuLines(emptyRowsPuzzles.get(3), sudokuPerLine, delim).forEach(System.out::println);

    System.out.println("\nEMPTY COLS (3) PUZZLES:");
    sudokuLines(emptyColsPuzzles.get(3), sudokuPerLine, delim).forEach(System.out::println);
    // emptyColsPuzzles.get(3).forEach(p -> System.out.println(p.toMedString()));

    System.out.println("\nEMPTY ROWS (3) PUZZLES:");
    sudokuLines(emptyRegionsPuzzles.get(3), sudokuPerLine, delim).forEach(System.out::println);
    // emptyRegionsPuzzles.get(3).forEach(p -> System.out.println(p.toMedString()));

    for (int a = 3; a < 9; a++) {
      System.out.printf("\nEMPTY AREAS (%d) PUZZLES:\n", a);
      sudokuLines(emptyAreasPuzzles.get(a), sudokuPerLine, delim).forEach(System.out::println);
      // emptyAreasPuzzles.get(a).forEach(p -> System.out.println(p.toMedString()));
    }
  }

  // Finds all 17 cell palindrome masks that satisfy the prime sieve,
  // and outputs their R values in range form.
  private static void wip3() {
    SudokuMask mask = SudokuMask.full();

    int clues = 17;
    int k = clues/2;
    long nck = Counting.NChooseKLong(40, k);

    long validMasks = 0L;
    long len = 0L;
    StringBuilder strb = new StringBuilder();
    for (long r = 0L; r < nck; r++) {
      mask.palindrome(clues, r);

      if (SudokuMask.satisfiesPrimeSieve(mask)) {
        // System.out.println(mask.toMedString());
        // System.out.printf("[%d] %s\n", r, mask.toStringDots());
        if (len == 0L) {
          // System.out.print(r);
          strb.append(r);
        }

        validMasks++;
        len++;
      } else {
        if (len > 1) {
          // System.out.println("-"+(r-1));
          strb.append("-"+(r-1));
        }
        if (len > 0) {
          strb.append(System.lineSeparator());
        }
        len = 0L;
        // strb.append(System.lineSeparator());
        if (strb.length() > 1024) {
          System.out.print(strb.toString());
          strb.delete(0, strb.length());
        }
      }
    }

    System.out.println(strb.toString());


    // mask.subtract(SudokuMask.REGION_MASKS[0]);
    // mask.subtract(SudokuMask.REGION_MASKS[4]);
    // mask.subtract(SudokuMask.REGION_MASKS[8]);

    // int validPuzzleCount = 0;
    // final int MAX_N = 1_000_000;
    // for (int n = 0; n < 1_000_000; n++) {
    //   Sudoku config = Sudoku.generateConfig();
    //   Sudoku p = config.filter(mask);
    //   if (p.solutionsFlag() == 1) {
    //     validPuzzleCount++;
    //   }
    //   // long numSolutions = p.countSolutions();


    //   // System.out.printf(
    //   //   "(%d) %s [%d] %s\n",
    //   //   n,
    //   //   numSolutions == 1L ? "✅" : "❌",
    //   //   numSolutions,
    //   //   p.toString()
    //   // );
    // }

    // System.out.printf(
    //   "Valid puzzles: %d (%d invalid) %s\n",
    //   validPuzzleCount,
    //   MAX_N - validPuzzleCount,
    //   validPuzzleCount == MAX_N ? "✅" : "❌"
    // );

    // SudokuMask mask = SudokuMask.full();
    // mask.subtract(SudokuMask.ROW_MASKS[0]);
    // mask.subtract(SudokuMask.ROW_MASKS[4]);
    // mask.subtract(SudokuMask.ROW_MASKS[8]);
    // mask.subtract(SudokuMask.COL_MASKS[0]);
    // mask.subtract(SudokuMask.COL_MASKS[4]);
    // mask.subtract(SudokuMask.COL_MASKS[8]);
    // mask.subtract(SudokuMask.REGION_MASKS[0]);
    // mask.subtract(SudokuMask.REGION_MASKS[4]);
    // // mask.subtract(SudokuMask.REGION_MASKS[8]);

    // Sudoku.searchForPuzzles(mask, p -> {
    //   System.out.println(p.toString());
    //   // System.out.println();
    //   return true;
    // });
  }

  // For quick testing. Changes frequently.
  // Generates a solution grid.
  // Generates a sieve.
  // Loops through all palindrome masks (given bitCount/clues), outputs valid puzzles.
  // Work split up into threads if specified.
  private static void adhoc() {
    // final boolean normalize = args.normalize();
    // final boolean prettyPrint = args.containsKey("pretty");
    final int threads = args.threads();
    // final int amount = args.amountOrDefault(1);
    final int clues = args.cluesOrDefault(27);

    final long startTimeMs = System.currentTimeMillis();

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }
    if (lines.isEmpty()) {
      lines.add(Sudoku.generateConfig().toString());
    }

    final long nck = Counting.nChooseK(40, clues/2).longValueExact();

    Sudoku solution = new Sudoku(lines.get(0));
    verboseOut("Searching for palindrome puzzles. Solution:");
    // System.out.println("Searching for palindrome puzzles. Solution:");
    System.out.println(solution.toString());

    SudokuSieve sieve = new SudokuSieve(solution);
    verboseOut("Seeding sieve...");
    sieve.seedThreaded(sieve.fullPrintCombos(4));
    verboseOut(sieve.toString());
    verboseOutf("Done. Sieve has %d items.\n", sieve.size());
    verboseOut("Starting sequential search...");

    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.MINUTES,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();

    AtomicLong found = new AtomicLong();
    final int numSplit = 1024;
    final long inc = Math.ceilDiv(nck, numSplit);
    Queue<Future<?>> workResultQueue = new LinkedList<>();
    final boolean verbose = args.isVerbose();
    for (long i = 0L; i < numSplit; i++) {
      final long start = i * inc;
      final long end = Math.min((i + 1) * inc, nck);
      workResultQueue.add(pool.submit(() -> {
        SudokuMask m = new SudokuMask();
        SudokuSieve _sieve = new SudokuSieve(solution);
        sieve.items().forEach(item -> _sieve.rawAdd(new SudokuMask(item)));
        for (long r = start; r < end; r++) {
          m.palindrome(clues, r);
          if (_sieve.doesMaskSatisfy(m)) {
            Sudoku p = solution.filter(m);
            if (isPrimePali(p)) {
              found.incrementAndGet();
              if (verbose) {
                System.out.printf("[%d] %s\n", r, p.toString());
              } else {
                System.out.println(p.toString());
              }
            }
          }
        }
      }));
    }

    pool.shutdown();
    while (!workResultQueue.isEmpty()) {
      try {
        workResultQueue.poll().get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }

    final long endTimeMs = System.currentTimeMillis();
    verboseOutf("Found %d palindrome puzzles (%d ms).\n", found.get(), (endTimeMs - startTimeMs));


    ///////////////////////////////////////////////////
    //  Kind of the same thing as above,
    //  but sequentially, not multithreaded.
    ///////////////////////////////////////////////////
    // long sieveTests = 0L;
    // long puzzleTests = 0L;
    // long puzzles = 0L;
    // int flag = 2;

    // long r = 0L;
    // SudokuMask m = new SudokuMask();
    // AtomicInteger solutionCount = new AtomicInteger();
    // AtomicBoolean addedToSieve = new AtomicBoolean();
    // while (r < nck) {
    //   // Accelerate r until the mask satisfies the sieve
    //   do {
    //     m.palindrome(clues, r);
    //     sieveTests++;
    //     r++;
    //   } while (!sieve.doesMaskSatisfy(m));

    //   Sudoku p = solution.filter(m);
    //   // System.out.printf(
    //   //   "✅ %s [%d] {ss %d} {st %d} {pt %d}\n",
    //   //   p.toString(), r, sieve.size(), sieveTests, puzzleTests
    //   // );
    //   solutionCount.set(0);
    //   addedToSieve.set(false);
    //   p.searchForSolutions(s -> {
    //     SudokuMask diffMask = solution.diffMask(s);
    //     solutionCount.incrementAndGet();
    //     if (sieve.add(diffMask)) {
    //       // System.out.println(" + " + solution.filterStr(diffMask));
    //       addedToSieve.set(true);
    //       return false;
    //     }
    //     return true;
    //   });
    //   flag = (!addedToSieve.get() && solutionCount.get() == 1) ? 1 : 2;
    //   puzzleTests++;
    //   if (flag == 1) {
    //     // System.out.printf("⭐️ %s [%d] {r %d}\n", p.toString(), puzzles++, r);
    //     System.out.printf("[%d] %s {r %d}\n", puzzles++, p.toString(), r);
    //   }
    // }
  }

  private static void benchy() {
    long startSysTime = System.currentTimeMillis();
    long[] benched;
    System.out.printf("%-24s %8s %8s   (milliseconds)\n%s\n", "function", "CPU time", "sys time", "-".repeat(24+9+9+17));

    System.out.printf("%-24s ", "solve sudoku17");
    benched = bench_all17Solve();
    System.out.printf("%8d %8d\n", benched[1], benched[0]);

    System.out.printf("%-24s ", "config gen (100)");
    benched = bench_configGen(100);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "config gen (1k)");
    benched = bench_configGen(1000);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "config gen (10k)");
    benched = bench_configGen(10000);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "config gen (100k)");
    benched = bench_configGen(100000);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);

    System.out.printf("%-24s ", "config gen old (100)");
    benched = bench_configGen_orig(100);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "config gen old (1k)");
    benched = bench_configGen_orig(1000);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "config gen old (10k)");
    benched = bench_configGen_orig(10000);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "config gen old (100k)");
    benched = bench_configGen_orig(100000);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);

    System.out.printf("%-24s ", "sieve gen (dc2)");
    benched = bench_sieveGen(2);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "sieve gen (dc3)");
    benched = bench_sieveGen(3);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);
    System.out.printf("%-24s ", "sieve gen (dc4)");
    benched = bench_sieveGen(4);
    System.out.printf("%8d %8d\n", benched[1], benched[0]);

    long endSysTime = System.currentTimeMillis();
    System.out.println("Complete.");
  }

  private static long[] bench_all17Solve() {
    long startSysTime = System.currentTimeMillis();
    long cpuTime = timeCpuExecution(() -> {
      for (PuzzleEntry entry : PuzzleEntry.allSudoku17()) {
        entry.puzzle().solution();
      }
    });
    long endSysTime = System.currentTimeMillis();
    return new long[]{endSysTime - startSysTime, cpuTime};
  }

  private static long[] bench_configGen(int amount) {
    long startSysTime = System.currentTimeMillis();
    long cpuTime = timeCpuExecution(() -> {
      for (int a = 0; a < amount; a++) {
        Sudoku.generateConfig();
      }
    });
    long endSysTime = System.currentTimeMillis();
    return new long[]{endSysTime - startSysTime, cpuTime};
  }

  private static long[] bench_configGen_orig(int amount) {
    long startSysTime = System.currentTimeMillis();
    long cpuTime = timeCpuExecution(() -> {
      for (int a = 0; a < amount; a++) {
        // The old Sudoku.generateConfig()
        Sudoku.configSeed().solution();
      }
    });
    long endSysTime = System.currentTimeMillis();
    return new long[]{endSysTime - startSysTime, cpuTime};
  }

  private static long[] bench_sieveGen(int level) {
    Sudoku grid = new Sudoku("821973546543186729967254831452397168198465273376812495219738654684529317735641982");
    long startSysTime = System.currentTimeMillis();
    long cpuTime = timeCpuExecution(() -> {
      SudokuSieve sieve = new SudokuSieve(grid);
      sieve.seed(sieve.digitCombos(level));
    });
    long endSysTime = System.currentTimeMillis();
    return new long[]{endSysTime - startSysTime, cpuTime};
  }

  // Also for quick experimenting. Often repurposed.
  private static void adhoc2() {
    int level = args.levelOrDefault(2);
    int threads = args.threads();
    long start = System.currentTimeMillis();

    // ------------------------------------------------------
    // Counts how many sudoku17 are solvable via simple reduction.
    // ------------------------------------------------------
    List<PuzzleEntry> all17 = PuzzleEntry.allSudoku17();
    AtomicInteger count = new AtomicInteger();
    all17.forEach(entry -> {
      Sudoku p = new Sudoku(entry.puzzle());
      p.resetCandidatesAndValidity();
      p.reduce();
      if (p.isSolved()) {
        System.out.println(entry.puzzle().toString());
        count.incrementAndGet();
      }
    });
    // System.out.printf("Total %d puzzles", count.get());

    // ------------------------------------------------------
    // Solves each sudoku17 (for simple benchmark purposes).
    // ------------------------------------------------------
    // for (PuzzleEntry entry : PuzzleEntry.all17()) {
    //   // System.out.println(entry.puzzle().solution().toString());
    //   entry.puzzle().solution();
    // }

    // ------------------------------------------------------
    // Generate entire collection of masks that satisfy prime sieve.
    // ------------------------------------------------------
    // SudokuMask.primeSieveHittingSetSearch();

    // ------------------------------------------------------
    // Read massive sieve masks file.
    // Write masks into separate files based on bitCount.
    // ------------------------------------------------------

    // long[] counts = new long[20];
    // PrintWriter[] writers = new PrintWriter[20];

    // try (
    //   BufferedReader readr = new BufferedReader(new FileReader(
    //     new File("./prime-sieve-search-out.txt")
    //   ))
    // ) {
    //   long el = 0L;
    //   final long EL_MAX = 1_000_000L;
    //   Pattern maskPattern = Pattern.compile("[.1]{81}");
    //   Matcher maskMatcher = maskPattern.matcher("");
    //   String line;
    //   StringBuilder strb = new StringBuilder();
    //   String lineSep = System.lineSeparator();
    //   while ((line = readr.readLine()) != null) {
    //     // el++;
    //     // if (el == EL_MAX) {
    //     //   System.out.print('.');
    //     //   el = 0L;
    //     // }
    //     if (line.length() == 81) {
    //       // System.out.println(line);
    //       // strb.append(line);
    //       // strb.append(lineSep);

    //       SudokuMask m = new SudokuMask(line);
    //       int bc = m.bitCount();
    //       if (counts[bc] == 0) {
    //         writers[bc] = new PrintWriter(String.format(
    //           "prime-sieve-items-%d.txt", bc
    //         ));
    //       }
    //       writers[bc].println(line);
    //       counts[bc]++;

    //     } else {
    //       // System.out.println("\n❌ " + line);
    //       if (line.length() > 81) {
    //         maskMatcher.reset(line);
    //         if (maskMatcher.find()) {
    //           String mStr = maskMatcher.group();
    //           // System.out.println(mStr);
    //           // strb.append(mStr);
    //           // strb.append(lineSep);

    //           SudokuMask m = new SudokuMask(mStr);
    //           int bc = m.bitCount();
    //           if (counts[bc] == 0) {
    //             writers[bc] = new PrintWriter(String.format(
    //               "prime-sieve-items-%d.txt", bc
    //             ));
    //           }
    //           writers[bc].println(mStr);
    //           counts[bc]++;
    //         }
    //       }
    //     }

    //     // if (strb.length() > 1_000_000) {
    //     //   System.out.print(strb.toString());
    //     // }
    //   }
    //   // if (strb.length() > 0) {
    //   //   System.out.print(strb.toString());
    //   // }
    //   // System.out.println();
    // } catch (IOException e) {
    //   e.printStackTrace();
    // }

    // for (PrintWriter pw : writers) {
    //   if (pw != null) {
    //     pw.close();
    //   }
    // }

    // ------------------------------------------------------
    // Read massive sieve masks file.
    // Check time and how much memory it costs to store data.
    // ------------------------------------------------------
    // reducePrimeSieveFiles();


    long end = System.currentTimeMillis();
    // System.out.println(end - start);


    // Sudoku config = Sudoku.generateConfig();
    // SudokuSieve sieve = new SudokuSieve(config);
    // sieve.seedThreaded(sieve.digitCombos(level), threads);

    // String originalFp = config.dc(level, threads);

    // Map<String, List<String>> fp2sToGrids = new HashMap<>();

    // // Gather all unique unavoidable sets (of any degree)
    // HashSet<String> uniqueSolutions = new HashSet<>();

    // fp2sToGrids.put(originalFp, new ArrayList<>());
    // fp2sToGrids.get(originalFp).add(config.toString());
    // uniqueSolutions.add(config.toString());

    // System.out.println(" ---- CONFIG ---- ");
    // System.out.printf(
    //   "%24s [%4d] -> %s\n",
    //   originalFp,
    //   1,
    //   config.toString()
    // );
    // System.out.println(" ---- ---- ");

    // sieve.digitCombos(level).forEach((mask) -> {
    //   config.filter(mask.flip()).searchForSolutions((solution) -> {
    //     String solutionStr = solution.toString();

    //     if (uniqueSolutions.add(solutionStr)) {
    //       String solutionFp2 = solution.dc2();

    //       if (!fp2sToGrids.containsKey(solutionFp2)) {
    //         fp2sToGrids.put(solutionFp2, new ArrayList<>());
    //       }

    //       fp2sToGrids.get(solutionFp2).add(solutionStr);

    //       // System.out.printf(
    //       //   "%24s [%4d] -> %s\n",
    //       //   solutionFp2,
    //       //   fp2sToGrids.get(solutionFp2).size(),
    //       //   solutionStr
    //       // );
    //     }
    //     return true;
    //   });
    // });




    // sieve.items().forEach(item -> {
    //   config.filter(item.flip()).searchForSolutions(s -> {
    //     String str = s.toString();

    //     if (uniqueSolutions.add(str)) {
    //       String fp = s.dc(level, threads);

    //       if (!fp2sToGrids.containsKey(fp)) {
    //         fp2sToGrids.put(fp, new ArrayList<>());
    //       }

    //       fp2sToGrids.get(fp).add(str);

    //       // System.out.printf(
    //       //   "%24s [%4d] -> %s\n",
    //       //   fp,
    //       //   fp2sToGrids.get(fp).size(),
    //       //   solutionStr
    //       // );
    //     }
    //     return true;

    //   });
    // });

    // System.out.println(" ---- MAP OUTPUT ---- ");

    // for (Entry<String,List<String>> entry : fp2sToGrids.entrySet()) {
    //   List<String> solutionStrs = entry.getValue();
    //   int size = solutionStrs.size();
    //   System.out.printf("[%4d] %s\n", size, entry.getKey());
    //   for (int i = 0; i < size; i++) {
    //     // String solutionStr = solutionStrs.get(i);

    //     String solutionStr = config.filterStr(config.diffMask(new Sudoku(solutionStrs.get(i))));
    //     System.out.printf("    [%4d] %s\n", i, solutionStr);
    //   }
    // }

  }

  private static class WorkItem {
    String input;
    String output;
    boolean isReady;

    WorkItem(String in) {
      this.input = in;
    }

    void setResult(String result) {
      this.output = result;
      isReady = true;
    }
  }

  // To be removed soon.
  private static void reducePrimeSieveFiles() {
    // See how much memory it takes to load files.
    // final String filename = args.getOrDefault("file", "").trim();
    final int threads = Math.max(4, args.threads());
    // if (filename == null || filename.isBlank()) {
    //   System.out.println("File required.");
    //   System.exit(1);
    // }
    File fin6 = new File("prime-masks7.txt");
    final String file2In = "prime-masks8.txt";
    final String file2Out = "prime-masks8.1.txt";
    // File fin8 = new File("prime-sieve-items-11.txt");
    // if (!fin.exists()) {
    //   System.out.println("File does not exist.\n"+filename);
    //   System.exit(1);
    // }
    // if (fin.isDirectory()) {
    //   System.out.println("File is a directory.\n"+filename);
    //   System.exit(1);
    // }

    List<SudokuMask> masks6 = new ArrayList<>();
    List<SudokuMask> masks8 = new ArrayList<>();
    try (
      BufferedReader reader6 = new BufferedReader(new FileReader(fin6));
      // BufferedReader reader8 = new BufferedReader(new FileReader(fin8));
      Scanner userInputScanner = new Scanner(System.in);
    ) {
      String line;
      while ((line = reader6.readLine()) != null) {
        masks6.add(new SudokuMask(line));
      }
      reader6.close();

      ThreadPoolExecutor pool = new ThreadPoolExecutor(
        threads, threads,
        1L, TimeUnit.MINUTES,
        new LinkedBlockingQueue<>()
      );

      LinkedBlockingDeque<WorkItem> work = new LinkedBlockingDeque<>();
      LinkedBlockingDeque<WorkItem> results = new LinkedBlockingDeque<>();

      // Workers will draw data from the queue to process.
      Runnable processWork = () -> {
        System.out.println("#️⃣ worker starting");
        while (!work.isEmpty()) {
          WorkItem item;
          try {
            item = work.poll(1L, TimeUnit.MINUTES);
            SudokuMask m8 = new SudokuMask(item.input);
            String m8Str = m8.toStringDots();
            boolean gucci = true;
            for (SudokuMask m6 : masks6) {
              if (m8.hasBitsSet(m6)) {
                gucci = false;
                break;
              }
            }
            // System.out.println(m8Str);
            item.setResult(gucci ? m8Str : null);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
        System.out.println("🛑 worker ending");
      };

      // One thread to read data into a shared queue.
      final int QUEUE_SIZE_THRESHOLD = 1_000_000;
      AtomicLong workRemaining = new AtomicLong();
      pool.submit(() -> {
        try {
          File fin8 = new File(file2In);
          BufferedReader reader8 = new BufferedReader(new FileReader(fin8));
          String elLine;
          while ((elLine = reader8.readLine()) != null) {
            workRemaining.incrementAndGet();
            while (results.size() > QUEUE_SIZE_THRESHOLD) {
              try {
                System.out.println("💤 waiting for shorter work queue");
                Thread.sleep(500L);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            }

            WorkItem item = new WorkItem(elLine);
            work.offer(item);
            results.offer(item);
          }
          reader8.close();
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      });

      try {
        Thread.sleep(3000L);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      for (int t = 0; t < threads; t++) {
        pool.submit(processWork);
      }

      pool.prestartAllCoreThreads();
      pool.shutdown();

      // Threads should be chugging away and processing work queue.
      // Read results as they come through.
      try {
        Thread.sleep(3000L);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      System.out.printf("Writing to %s...\n", file2Out);
      final long MAX_RESULTS_WAIT_TIME_MS = 60_000L;
      PrintWriter pw = new PrintWriter(file2Out);
      while (!results.isEmpty()) {
        try {
          WorkItem item = results.poll(1L, TimeUnit.MINUTES);
          if (item == null) continue;
          long s = System.currentTimeMillis();
          while (
            !item.isReady &&
            (System.currentTimeMillis() - s) < MAX_RESULTS_WAIT_TIME_MS
          ) {
            // System.out.println("💤 waiting on item result");
            Thread.sleep(50L);
          }
          if (item.output == null) {
            // System.out.printf("❌ %s\n", item.input);
          } else {
            pw.println(item.output);
            System.out.println(item.output);
          }
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
      pw.close();
      System.out.println("Done.");

      try {
        pool.awaitTermination(1L, TimeUnit.DAYS);
        System.out.println("All tasks completed!");
      } catch (InterruptedException e) {
        e.printStackTrace();
      } finally {
        System.out.print("Closing thread pool... ");
        pool.close();
      }
      System.out.println("done.");

      System.out.printf("Read %d masks from file(6).\n", masks6.size());
      System.out.printf("Read in %d masks from file(8).\n", masks8.size());
      long memBytes = Runtime.getRuntime().totalMemory();
      long memMBs = memBytes / 1_000_000L;
      long maxMemBytes = Runtime.getRuntime().maxMemory();
      long maxMemMBs = maxMemBytes / 1_000_000L;
      System.out.printf("Memory usage: %d MB / %d MB\n", memMBs, maxMemMBs);

      // System.out.println("Checking 8 for derivatives of 6...");
      // int masks8SizeBefore = masks8.size();
      // int m6Indent = (1 + ((Double)Math.ceil(Math.log10(masks6.size()))).intValue());
      // for (int m6i = 0; m6i < masks6.size(); m6i++) {
      //   SudokuMask m6 = masks6.get(m6i);
      //   // String m6Str = m6.toStringDots();
      //   int m8SizeBefore = masks8.size();
      //   // System.out.printf("[%"+m6Indent+"d] %s\n", m6i, m6Str);
      //   // if (masks8.removeIf(m8 -> m8.hasBitsSet(m6))) {
      //   //   System.out.printf(
      //   //     "[%"+m6Indent+"d] %s\n   -%d\n",
      //   //     m6i, m6Str,
      //   //     m8SizeBefore - masks8.size()
      //   //   );
      //   // }

      //   masks8.removeIf(m8 -> m8.hasBitsSet(m6));

      //   // for (int m8i = masks8.size() - 1; m8i >= 0; m8i--) {
      //   //   SudokuMask m8 = masks8.get(m8i);
      //   //   if (m8.hasBitsSet(m6)) {
      //   //     // DERIVATIVE FOUND - REMOVE FROM masks8
      //   //     masks8.remove(m8i);
      //   //     // System.out.printf(" %"+m6Indent+"sx %s\n", "", m8.toStringDots());
      //   //   }
      //   // }

      //   if (masks8.size() < m8SizeBefore) {
      //     System.out.printf(
      //       "[%"+m6Indent+"d]   -%6d  /  %d\n",
      //       m6i,
      //       m8SizeBefore - masks8.size(),
      //       masks8.size()
      //     );
      //   }
      // }

      // // Output final results / stats
      // System.out.printf(
      //   "Removed %d masks8s. Size: %d.\n",
      //   masks8SizeBefore - masks8.size(),
      //   masks8.size()
      // );

      // Print out surviving masks to new prime-sieve-items file.
      // System.out.print("Writing to prime-masks11.txt... ");
      // PrintWriter pw = new PrintWriter("prime-masks11.txt");
      // for (SudokuMask m : masks8) {
      //   pw.println(m.toStringDots());
      // }
      // pw.close();
      // System.out.println("done.");

      // Wait for my signal before exiting.
      // I want to check process management to see how much memory is used.
      System.out.print("\nPress ENTER to exit...\n > ");
      userInputScanner.nextLine();
      userInputScanner.close();

    } catch (IOException ex) {
      ex.printStackTrace();
    }

    //
    // 274919760 prime-masks7.txt
    // 2636530092 prime-masks8.txt
    //
    //
    // 281755116 prime-masks11.txt
  }

  // Performs a sieve search for the given grid, confined by a given number of clues.
  public static void sieveSearch() {
    final int threads = args.threads();
    final int clues = args.clues();
    final String algo = args.algoOrDefault("fp3");

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR sieveSearch: No grid(s) given.");
      System.exit(1);
    }

    if (threads > 1) verboseOutf("Using %d threads.\n", threads);

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR sieveSearch: Specified grid is malformed.");
        System.exit(1);
      }

      verboseOutf("Searching with grid:\n%s\n", grid.toString());

      SudokuSieve sieve = new SudokuSieve(grid);
      verboseOutf("Seeding sieve (algo %s; threads %d)...", algo, threads);
      long seedStartTime = timeMs();
      switch (algo) {
        case "dc2": sieve.seedThreaded(sieve.digitCombos(2), threads); break;
        case "dc3": sieve.seedThreaded(sieve.digitCombos(3), threads); break;
        case "dc4": sieve.seedThreaded(sieve.digitCombos(4), threads); break;
        case "fp2": sieve.seedThreaded(sieve.fullPrintCombos(2), threads); break;
        case "fp3": sieve.seedThreaded(sieve.fullPrintCombos(3), threads); break;
        case "fp4": sieve.seedThreaded(sieve.fullPrintCombos(4), threads); break;
        default: throw new RuntimeException("Something went wrong creating fingerprint.");
      }
      sieve.seedThreaded(sieve.digitCombos(2), threads);
      long seedEndTime = timeMs();
      verboseOutf(
        "Done in %d ms.\n%s\nSieve has %d items.\n",
        seedEndTime - seedStartTime,
        sieve.toString(),
        sieve.size()
      );

      SieveSearcher searcher = new SieveSearcher(sieve);
      out("Starting in just a sec...");
      sleep(2000L);
      searcher.search(clues);
      out("Done searching.");
    }
  }

  // Performs a sieve search using the given grid.
  // Max number of clues is confined by the minimum of the search results,
  // decreasing as results with fewer clues are found.
  //
  // This should find the minimum sudoku puzzles for the given grid.
  public static void minSearch() {
    final int threads = args.threads();
    final String algo = args.algoOrDefault("fp3");

    if (args.containsKey("grid")) {
      lines.add(args.get("grid"));
    }

    if (lines.isEmpty()) {
      System.err.println("ERROR sieveSearch: No grid(s) given.");
      System.exit(1);
    }

    if (threads > 1) verboseOutf("Using %d threads.\n", threads);

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR sieveSearch: Specified grid is malformed.");
        System.exit(1);
      }

      verboseOutf("Searching with grid:\n%s\n", grid.toString());

      // Create and seed a sieve
      SudokuSieve sieve = new SudokuSieve(grid);
      verboseOutf("Seeding sieve (algo %s; threads %d)...", algo, threads);
      long seedStartTime = timeMs();
      switch (algo) {
        case "dc2": sieve.seedThreaded(sieve.digitCombos(2), threads); break;
        case "dc3": sieve.seedThreaded(sieve.digitCombos(3), threads); break;
        case "dc4": sieve.seedThreaded(sieve.digitCombos(4), threads); break;
        case "fp2": sieve.seedThreaded(sieve.fullPrintCombos(2), threads); break;
        case "fp3": sieve.seedThreaded(sieve.fullPrintCombos(3), threads); break;
        case "fp4": sieve.seedThreaded(sieve.fullPrintCombos(4), threads); break;
        default: throw new RuntimeException("Something went wrong creating fingerprint.");
      }
      sieve.seedThreaded(sieve.digitCombos(2), threads);
      long seedEndTime = timeMs();
      verboseOutf(
        "Done in %d ms.\n%s\nSieve has %d items.\n",
        seedEndTime - seedStartTime,
        sieve.toString(),
        sieve.size()
      );

      SieveSearcher searcher = new SieveSearcher(sieve);
      out("Starting in just a sec...");
      sleep(2000L);
      searcher.minsearch();
      out("Done searching.");
    }
  }

  // Reports the cpu execution time of Sudoku.generateConfig().
  private static void benchConfigGeneration() {
    final int MAX_AMOUNT = Integer.MAX_VALUE;
    defaultInMap(args, "amount", "1");
    int amount = inBounds(Integer.parseInt(args.get("amount")), 1, MAX_AMOUNT);

    // TODO Adapt for multithreading
    // defaultInMap(args, "threads", "1");
    // int numThreads = inBounds(Integer.parseInt(args.get("threads")), 1, Runtime.getRuntime().availableProcessors());

    List<Sudoku> configs = new ArrayList<>();
    long time = timeCpuExecution(() -> {
      for (int t = 0; t < amount; t++) {
        configs.add(Sudoku.generateConfig());
      }
    });

    outf(
      "benchConfigGeneration({ amount: %d }): %d ms\n",
      amount,
      TimeUnit.NANOSECONDS.toMillis(time)
    );
  }

  // Checks all sudoku 17 puzzles and reports the number of unique solutions
  // and unique fp2.
  private static void check17() {
    long startTime = System.currentTimeMillis();
    System.out.print("Reading in 17-clue puzzles...");
    PuzzleEntry[] sudoku17 = PuzzleEntry.all17();
    System.out.println(" ✅.");

    // Use maximum of 8 processors while keeping 2 available for the system to keep doing its thing.
    int numThreads = Runtime.getRuntime().availableProcessors();
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      numThreads, numThreads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();

    System.out.println("Solving all puzzles" + (numThreads > 1 ? " ["+numThreads+" threads]." : "."));
    Set<String> solutions = Collections.synchronizedSet(new HashSet<>());
    Set<String> fp2s = Collections.synchronizedSet(new HashSet<>());
    for (int i = 0; i < sudoku17.length; i++) {
      final int j = i;
      pool.submit(() -> {
        PuzzleEntry entry = sudoku17[j];
        solutions.add(entry.solutionStr());
        fp2s.add(entry.fp2());
      });
    }

    pool.shutdown();
    try {
      pool.awaitTermination(10L, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      e.printStackTrace();
      pool.shutdownNow();
    }

    long endTime = System.currentTimeMillis();
    System.out.println("Finished in " + (endTime - startTime) + " ms.");
    System.out.printf("Found %d unique solutions.\n", solutions.size());
    System.out.printf("Found %d unique fp2s.\n", fp2s.size());
  }
}
