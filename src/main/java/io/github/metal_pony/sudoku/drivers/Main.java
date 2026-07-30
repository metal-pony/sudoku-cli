package io.github.metal_pony.sudoku.drivers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.Set;
import java.util.Stack;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.github.metal_pony.sudoku.PuzzleEntry;
import io.github.metal_pony.sudoku.Sudoku;
import io.github.metal_pony.sudoku.SudokuMask;
import io.github.metal_pony.sudoku.SudokuSieve;
import io.github.metal_pony.sudoku.util.ArraysUtil;
import io.github.metal_pony.sudoku.util.Counting;

public class Main {
  private static final String DEFAULT_COMMAND = "generateSolutions";

  private static final List<String> lines = new ArrayList<>();
  private static final ArgsMap args = new ArgsMap();
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
    put("benchConfigs", Main::benchConfigGeneration);
    // --amount %d --clues %d --threads %d
    put("generatePuzzles", Main::generatePuzzles);
    put("countSolutions", Main::countSolutions);
    // --puzzle %s --threads %d --timeout %d
    put("solve", Main::solve);
    put("generateBands", GenerateInitialBands::generateInitialBands);
    // --level %d --grid %s --threads %d
    put("sieve", Main::createSieve);
    // --level %d --grid %s --threads %d
    put("fingerprint", Main::fingerprint);

    // For testing / experimentation
    put("adhoc", Main::adhoc);
    put("help", Main::help);
    put("check17", Main::check17);
    put("buildcsv", Main::buildcsv);
    put("process17", Main::process17s);
    put("dj", Main::createDisjointMaps);
    put("countCompare", Main::compareCountSolutions);

    put("buildSieveTest", Main::buildSieveTestCSV);
    put("buildjson17", Main::sudoku17ToJSON);
  }};

  // private static String padLeft(String str, int length, char fillChar) {
  //   return Character.toString(fillChar).repeat(length - str.length()) + str;
  // }

  private static void sudoku17ToJSON(ArgsMap args) {
    long start = System.currentTimeMillis();

    // Use maximum of 8 processors while keeping 2 available for the system to keep doing its thing.
    int numThreads = inBounds(Runtime.getRuntime().availableProcessors() - 2, 1, 8);
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      numThreads, numThreads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );

    System.out.println("[");
    PuzzleEntry.allSudoku17().forEach(entry -> {
      pool.submit(() -> {
        System.out.println(entry.toString() + ",");
      });
    });

    pool.shutdown();
    try {
      pool.awaitTermination(1L, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    System.out.println("]");

    long end = System.currentTimeMillis();
    long total = end - start;
    long mins = total / 60000L;
    long secs = (total - (60000L * mins)) / 1000L;
    String timeStr = (mins > 0) ? String.format("%d min, %d sec", mins, secs) : String.format("%d sec", secs);
    System.out.printf("Done (%s)\n", timeStr);
  }

  private static void process17s(ArgsMap args) {
    long start = System.currentTimeMillis();

    PuzzleEntry[] sudoku17 = PuzzleEntry.all17();

    // Use maximum of 8 processors while keeping 2 available for the system to keep doing its thing.
    int numThreads = inBounds(Runtime.getRuntime().availableProcessors() - 2, 1, 8);
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      numThreads, numThreads,
      10L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );

    String debug_setName = "dc4";
    List<Future<String[]>> ftrs = new ArrayList<>();
    for (PuzzleEntry entry : sudoku17) {
      ftrs.add(pool.submit(() -> {
        Sudoku solution = new Sudoku(entry.solution());
        return new String[]{
          entry.solutionStr(),
          // solution.dc2(),
          // solution.dc3(),
          solution.dc4(),
          // solution.ac2(),
          // solution.ac3(),
          // solution.ac4(),
          // solution.fp2(),
          // solution.fp3(),
          // solution.fp4()
        };
      }));
    }

    pool.shutdown();

    // HashSet<String> dc2Set = new HashSet<>();
    // HashSet<String> dc3Set = new HashSet<>();
    HashSet<String> dc4Set = new HashSet<>();

    // HashSet<String> ac2Set = new HashSet<>();
    // HashSet<String> ac3Set = new HashSet<>();
    // HashSet<String> ac4Set = new HashSet<>();

    // HashSet<String> fp2Set = new HashSet<>();
    // HashSet<String> fp3Set = new HashSet<>();
    // HashSet<String> fp4Set = new HashSet<>();

    // Only used for counting;
    // int fi = 0;
    while (!ftrs.isEmpty()) {
      Future<String[]> ftr = ftrs.remove(0);
      try {
        String[] prints = ftr.get(1L, TimeUnit.MINUTES);
        // dc2Set.add(prints[1]);
        // dc3Set.add(prints[2]);
        dc4Set.add(prints[1]);

        // ac2Set.add(prints[4]);
        // ac3Set.add(prints[5]);
        // ac4Set.add(prints[6]);

        // fp2Set.add(prints[7]);
        // fp3Set.add(prints[1]);
        // fp4Set.add(prints[9]);
        System.out.println(String.join(",", prints));
      } catch (InterruptedException | ExecutionException | TimeoutException e) {
        e.printStackTrace();
      }
      // fi++;
    }

    long end = System.currentTimeMillis();
    long total = end - start;
    long mins = total / 60000L;
    long secs = (total - (60000L * mins)) / 1000L;
    String timeStr = (mins > 0) ? String.format("%d min, %d sec", mins, secs) : String.format("%d sec", secs);
    System.out.printf("Done (%s). FP counts:\n", timeStr);
    System.out.printf(
      // "dc4: %4d\n", dc4Set.size()
      "%s: %4d\n", debug_setName, dc4Set.size()

      // "dc2: %4d        ac2: %4d        fp2: %4d\ndc3: %4d        ac3: %4d        fp3: %4d\ndc4: %4d        ac4: %4d        fp4: %4d\n",
      //dc2Set.size(), ac2Set.size(), fp2Set.size(),
      // dc3Set.size(), ac3Set.size(), fp3Set.size(),
      // dc4Set.size(), ac4Set.size(), fp4Set.size()
    );
  }

  // TODO update help content
  private static void help(ArgsMap args) {
    System.out.println(
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

  private static void play(ArgsMap args) {
    defaultInMap(args, "clues", "27");
    int clues = inBounds(Integer.parseInt(args.get("clues")), 17, 81);
    Sudoku puzzle = Sudoku.generatePuzzle(clues);
    SudokuGuiDemo.show(puzzle);
  }

  private static void generateConfigs(ArgsMap args) {
    defaultInMap(args, "amount", "1");
    defaultInMap(args, "threads", "1");

    final int numConfigs = Math.max(Integer.parseInt(args.get("amount")), 1);
    final int numThreads = inBounds(Integer.parseInt(args.get("threads")), 1, 8);
    final boolean normalize = args.containsKey("normalize");

    List<Future<?>> ftrs = Collections.synchronizedList(new ArrayList<>(numConfigs));
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      numThreads, numConfigs,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();

    long start = System.currentTimeMillis();
    for (int n = 0; n < numConfigs; n++) {
      ftrs.add(
        pool.submit(() -> {
          Sudoku config = Sudoku.generateConfig();
          if (normalize) config.normalize();
          System.out.println(config.toString());
          return config.toString();
        })
      );
    }

    pool.shutdown();
    for (Future<?> f : ftrs) {
      try {
        f.get();
      } catch (InterruptedException | ExecutionException e) {
        e.printStackTrace();
      }
    }

    if (args.isVerbose()) {
      System.out.printf(
        "Generated %d configs in %d ms.\n",
        // "Generated %d configs in %d ms. %d different fps.\n",
        numConfigs,
        System.currentTimeMillis() - start//,
        // fps.size()
      );
    }
  }

  // TODO Adapt for multiple threads
  // TODO Output as json
  // TODO Difficulty option
  private static void generatePuzzles(ArgsMap args) {
    defaultInMap(args, "amount", "1");
    defaultInMap(args, "clues", "27");
    defaultInMap(args, "threads", "1");

    String gridStr = args.get("grid");
    boolean useSameSolution = (gridStr != null);
    Sudoku grid = (gridStr == null) ? Sudoku.generateConfig() : new Sudoku(gridStr);

    final int amount = inBounds(Integer.parseInt(args.get("amount")), 1, 1_000_000);
    final int clues = inBounds(Integer.parseInt(args.get("clues")), 19, Sudoku.SPACES);
    // TODO Generate with multiple threads
    // final int threads = inBounds(Integer.parseInt(args.get("threads")), 1, MAX_THREADS);

    SudokuSieve sieve = new SudokuSieve(grid);

    for (int n = 0; n < amount; n++) {
      Sudoku puzzle = Sudoku.generatePuzzle(
        useSameSolution ? grid : null,
        clues,
        useSameSolution ? sieve : null,
        0,
        60*1000L
      );
      if (puzzle == null) {
        // Timed out
        return;
      } else {
        System.out.println(puzzle);
      }
    }
  }

  private static void countSolutions(ArgsMap args) {
    defaultInMap(args, "threads", "1");

    String gridStr = args.get("grid");
    Sudoku grid = (gridStr == null) ? Sudoku.configSeed().solution() : new Sudoku(gridStr);
    final int numThreads = inBounds(Integer.parseInt(args.get("threads")), 1, Runtime.getRuntime().availableProcessors());

    long start = System.currentTimeMillis();
    long numSolutions = 0L;
    if (numThreads == 1) {
      if (args.isVerbose()) {
        System.out.printf("countSolutions(\n  grid: %s\n  numThreads: %d\n):\n", grid.toString(), numThreads);
      }
      numSolutions = grid.countSolutionsAsync(numThreads);
    } else {

    }
    long end = System.currentTimeMillis();
    if (args.isVerbose()) System.out.printf("Total: %d\n", numSolutions);
    if (args.isVerbose()) System.out.printf("(%d ms)\n", end - start);
    System.out.println(numSolutions);
  }

  private static void solve(ArgsMap args) {
    System.out.println(new Sudoku(args.get("grid")).solution().toString());
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

  public static void main(String[] args) throws IOException, ClassNotFoundException {
    ArgsMap argsMap = ArgsMap.parseCommandLineArgs(args, 1);

    String command = DEFAULT_COMMAND;
    if (args != null) {
      if (args.length >= 1) {
        command = args[0];
      }
    }

    if (!COMMANDS.containsKey(command)) {
      System.out.println("Sudoku: Command not recognized.");
      System.exit(1);
    }

    COMMANDS.get(command).accept(argsMap);
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

  static long timeCpuExecution(Runnable runnable) {
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    long start = bean.getCurrentThreadCpuTime();
    runnable.run();
    long end = bean.getCurrentThreadCpuTime();
    return end - start;
  }

  /**
   * level [2,4] (default: 2)
   * grid? [str] (default: randomly generated)
   * threads? [1, #cores - 2] (default: 1 if omitted; MAX if only "--threads" given)
   */
  public static void createSieve(ArgsMap args) {
    final int MIN_LEVEL = 2;
    final int MAX_LEVEL = 4;

    defaultInMap(args, "level", "2");
    defaultInMap(args, "threads", "1");

    String gridStr = args.get("grid");
    int level = inBounds(Integer.parseInt(args.get("level")), MIN_LEVEL, MAX_LEVEL);
    int numThreads = inBounds(Integer.parseInt(args.get("threads")), 1, Runtime.getRuntime().availableProcessors());

    Sudoku grid = (gridStr == null) ? Sudoku.generateConfig() : new Sudoku(gridStr);
    System.out.println(grid.toString());
    SudokuSieve sieve = new SudokuSieve(grid);

    if (args.isVerbose()) System.out.println("Using " + numThreads + " threads.");
    long startTime = System.currentTimeMillis();
    sieve.seedThreaded(sieve.fullPrintCombos(level), numThreads);
    long endTime = System.currentTimeMillis();

    System.out.println(sieve.toString());
    System.out.printf("Added %d items to sieve (%d ms).\n", sieve.size(), endTime - startTime);
  }

  public static void fingerprint(ArgsMap args) {
    final int MIN_LEVEL = 2;
    final int MAX_LEVEL = 4;
    final String ALL_LEVELS = "2,3,4";

    defaultInMap(args, "level", "2");
    defaultInMap(args, "threads", "1");

    String gridStr = args.get("grid");
    Sudoku grid = (gridStr == null) ? Sudoku.configSeed().solution() : new Sudoku(gridStr);

    String threadsStr = args.get("threads");
    if ("max".equalsIgnoreCase(threadsStr)) {
      threadsStr = Integer.toString(Runtime.getRuntime().availableProcessors());
    }
    int numThreads = inBounds(Integer.parseInt(threadsStr), 1, Runtime.getRuntime().availableProcessors());

    String levelArg = args.get("level");
    if ("all".equals(levelArg)) { levelArg = ALL_LEVELS; }

    if (args.isVerbose()) {
      System.out.printf("Calculating fingerprint of grid:\n%s\n", grid.toString());
    }

    if (numThreads > 1) {
      System.out.printf("Using %d threads.\n", numThreads);
    }

    if (levelArg.contains(",")) {
      for (String lvlStr : levelArg.split(",")) {
        try {
          int level = inBounds(Integer.parseInt(lvlStr), MIN_LEVEL, MAX_LEVEL);
          long start = System.currentTimeMillis();
          String fp = grid.fp(level, numThreads);
          long sysTime = System.currentTimeMillis() - start;
          if (args.isVerbose()) {
            System.out.printf("fingerprint (level %d): %s (%d ms)\n", level, fp, sysTime);
          } else {
            System.out.println(fp);
          }
        } catch (NumberFormatException numbrEx) {
          System.out.println("Error: unrecognized level");
        }
      }

    } else {
      try {
        int level = inBounds(Integer.parseInt(args.get("level")), MIN_LEVEL, MAX_LEVEL);
        long start = System.currentTimeMillis();
        String fp = grid.fp(level, numThreads);
        long sysTime = System.currentTimeMillis() - start;
        if (args.isVerbose()) {
          System.out.printf("fingerprint (level %d): %s (%d ms)\n", level, fp, sysTime);
        } else {
          System.out.println(fp);
        }
      } catch (NumberFormatException numbrEx) {
        System.out.println("Error: unrecognized level");
      }
    }

    // String format = "{\"%s\", \"%s\"},\n";
    // System.out.printf(format, "dc2", grid.dc2());
    // System.out.printf(format, "dc3", grid.dc3());
    // System.out.printf(format, "dc4", grid.dc4());
    // System.out.printf(format, "ac2", grid.ac2());
    // System.out.printf(format, "ac3", grid.ac3());
    // System.out.printf(format, "ac4", grid.ac4());
    // System.out.printf(format, "fp2", grid.fp2());
    // System.out.printf(format, "fp3", grid.fp3());
    // System.out.printf(format, "fp4", grid.fp4());
  }

  // CURRENTLY: Sieve search
  public static void adhoc(ArgsMap args) {
    final int MIN_LEVEL = 2;
    final int MAX_LEVEL = 4;


    // Sudoku g = Sudoku.generateConfig();
    // Sudoku g = new Sudoku("653842197721596438894317526248731965539624781176958243415283679362179854987465312");
    // long start = System.currentTimeMillis();
    // SudokuSieve gSieve = new SudokuSieve(g);
    // gSieve.seedThreaded(gSieve.fullPrintCombos(3), 8);
    // long end = System.currentTimeMillis();

    // System.out.println("     "+g.toString());
    // int sum = 0;
    // for (SudokuMask item : gSieve.items(new ArrayList<>())) {
    //   System.out.printf("[%2d] %s\n", item.bitCount(), g.filterStr(item));
    //   sum += item.bitCount();
    // }
    // System.out.println("total: " + sum + " unavoidable set cells from " + gSieve.size() + " sets.");
    // System.out.println(g.fp3());
    // System.out.printf("Done in %d ms\n", end - start);


    // defaultInMap(args, "amount", "1");
    defaultInMap(args, "level", "2");
    defaultInMap(args, "clues", "81");
    // defaultInMap(args, "threads", "1");

    String gridStr = args.get("grid");
    System.out.println("Using sudoku grid:");
    Sudoku grid = (gridStr == null) ? Sudoku.generateConfig() : new Sudoku(gridStr);
    System.out.println(grid.toString());
    SudokuSieve sieve = new SudokuSieve(grid);
    int level = inBounds(Integer.parseInt(args.get("level")), MIN_LEVEL, MAX_LEVEL);
    int clues = inBounds(Integer.parseInt(args.get("clues")), Sudoku.MIN_CLUES, Sudoku.SPACES);
    // final int amount = Math.max(Integer.parseInt(args.get("amount")), 1);
    // int numThreads = inBounds(Integer.parseInt(args.get("threads")), 1, Runtime.getRuntime().availableProcessors());

    // SIEVE SEARCHING
    int numThreads = MAX_THREADS;
    System.out.printf("Seeding sieve (level %d; threads %d)...", level, numThreads);
    long seedStartTime = System.currentTimeMillis();
    sieve.seedThreaded(sieve.fullPrintCombos(level), MAX_THREADS);
    long seedEndTime = System.currentTimeMillis();
    System.out.printf("Done in %d ms.\n", seedEndTime - seedStartTime);
    // System.out.println(sieve.toString());
    System.out.printf("Sieve has %d items.\n", sieve.size());

    SieveSearcher searcher = new SieveSearcher(sieve);
    System.out.println("Starting in just a sec...");
    sleep(2000L);

    searcher.search(clues);
    System.out.println("Done searching.");
  }

  // private static int[] arrCopy(int[] arr) {
  //   int[] copy = new int[arr.length];
  //   System.arraycopy(arr, 0, copy, 0, arr.length);
  //   return copy;
  // }

  // private static String join(int[] arr) {
  //   StringBuilder strb = new StringBuilder();
  //   for (int n : arr)
  //     strb.append(n);
  //   return strb.toString();
  // }

  // private static int minOfMaxIndices(SudokuSieve sieve) {
  //   int min = 81;
  //   for (SudokuMask item : sieve.items()) {
  //     min = Math.min(maxIndex(item), min);
  //   }
  //   return min;
  // }

  // private static int maxIndex(SudokuMask mask) {
  //   return last(mask.toIndices());
  // }

  // private static int last(int[] arr) {
  //   if (arr.length == 0) throw new RuntimeException("arr is empty");
  //   return arr[arr.length - 1];
  // }

  private static void benchConfigGeneration(ArgsMap args) {
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

    System.out.printf(
      "benchConfigGeneration({ amount: %d }): %d ms\n",
      amount,
      TimeUnit.NANOSECONDS.toMillis(time)
    );
  }

  private static void check17(ArgsMap args) {
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

  static void buildcsv(ArgsMap args) {
    PuzzleEntry.buildCSV("out/sudoku-17.csv", 8);
  }

  static void buildSieveTestCSV(ArgsMap args) {
    defaultInMap(args, "numEntries", "8");
    int numEntries = Integer.parseInt(args.get("numEntries"));

    for (int n = 0; n < numEntries; n++) {
      Sudoku grid = Sudoku.generateConfig();
      SudokuSieve sieve = new SudokuSieve(grid);
      sieve.seedThreaded(sieve.fullPrintCombos(2));
      List<String> itemStrsSorted = new ArrayList<>();
      sieve.items().forEach(item -> itemStrsSorted.add("\""+item.toString()+"\""));
      itemStrsSorted.sort(String::compareTo);
      System.out.printf("%s;2;%d;[%s]\n", grid.toString(), sieve.size(), String.join(",", itemStrsSorted));

      sieve = new SudokuSieve(grid);
      sieve.seedThreaded(sieve.fullPrintCombos(3));
      itemStrsSorted.clear();
      sieve.items().forEach(item -> itemStrsSorted.add("\""+item.toString()+"\""));
      itemStrsSorted.sort(String::compareTo);
      System.out.printf("%s;3;%d;[%s]\n", grid.toString(), sieve.size(), String.join(",", itemStrsSorted));
    }
  }

  public static void createDisjointMaps(ArgsMap args) {
    final int MIN_LEVEL = 2;
    final int MAX_LEVEL = 4;
    defaultInMap(args, "level", "2");
    defaultInMap(args, "threads", "1");
    String gridStr = args.get("grid");
    Sudoku grid = (gridStr == null) ? Sudoku.configSeed().solution() : new Sudoku(gridStr);
    int level = inBounds(Integer.parseInt(args.get("level")), MIN_LEVEL, MAX_LEVEL);
    int numThreads = inBounds(Integer.parseInt(args.get("threads")), 1, Runtime.getRuntime().availableProcessors());

    System.out.printf("createDisjointMaps({ grid: %s, level: %d, threads: %d })\n", grid.toString(), level, numThreads);

    System.out.printf("Creating and seeding sieve (level %d)... ", level);
    long start = System.currentTimeMillis();
    SudokuSieve sieve = new SudokuSieve(grid);
    sieve.seed(sieve.digitCombos(level));
    long end = System.currentTimeMillis();
    System.out.printf("done (%d ms).\n", end - start);
    System.out.printf("Sieve contains %d items.\n", sieve.size());
    System.out.println(sieve.toString());

    System.out.println("Creating an index of all disjoint sets...");
    start = System.currentTimeMillis();
    Set<SudokuMask> items = sieve.items();
    SetArray<SudokuMask> disjointSetsMap = new SetArray<>(81);
    Queue<DjNode> q = new LinkedList<>();
    for (SudokuMask m : items) {
      DjNode node = new DjNode(1, new SudokuMask(m));
      q.offer(node);
      disjointSetsMap.add(node.mask, node.n);
      System.out.printf("[%d] [%2d] %s\n", disjointSetsMap.size() - 1, node.n, grid.filterStr(node.mask));
    }

    while (!q.isEmpty()) {
      DjNode curNode = q.poll();

      curNode.nexts(items, nextNode -> {
        if (disjointSetsMap.add(nextNode.mask, nextNode.n)) {
          System.out.printf("[%d] [%2d] %s\n", disjointSetsMap.size() - 1, nextNode.n, grid.filterStr(nextNode.mask));
          q.offer(nextNode);
        }
      });
    }
    end = System.currentTimeMillis();
    System.out.printf("Finished (%d ms); found %d total disjointed sets.\n", end - start, disjointSetsMap.size());

    for (int n = 0; n < disjointSetsMap.length; n++) {
      Set<SudokuMask> set = disjointSetsMap.list.get(n);
      if (set.size() > 0) {
        System.out.printf("[%2d]: %d\n", n, set.size());
      }
    }

  }

  public static class DisjointSetsMap extends SetArray<SudokuMask> {
    Sudoku grid;

    public DisjointSetsMap(Sudoku grid) {
      super(Sudoku.SPACES);
      this.grid = grid;
    }

    public void dumpTo(DisjointSetsMap other) {
      for (int i = length - 1; i > 0; i--) {
        if (!list.get(i).isEmpty()) {
          for (SudokuMask item : list.get(i)) {
            other.add(item, i);
          }
          list.get(i).clear();
        }
      }
      size = 0;
    }

    public void splitOutOverlapping(SudokuMask mask, DisjointSetsMap other) {
      for (int i = length - 1; i > 0; i--) {
        final int _i = i;
        list.get(i).removeIf(item -> {
          boolean intersects = mask.intersects(item);
          if (intersects) {
            other.add(item, _i);
            size--;
          }
          return intersects;
        });
      }
    }

    public void build(int level) {
      SudokuSieve sieve = new SudokuSieve(grid);
      sieve.seedThreaded(sieve.digitCombos(level));
      Set<SudokuMask> items = sieve.items();

      Queue<DjNode> q = new LinkedList<>();
      for (SudokuMask m : items) {
        DjNode node = new DjNode(1, new SudokuMask(m));
        q.offer(node);
        add(node.mask, node.n);
        // System.out.printf("[%d] [%2d] %s\n", size() - 1, node.n, grid.filterStr(node.mask));
      }

      while (!q.isEmpty()) {
        DjNode curNode = q.poll();

        curNode.nexts(items, nextNode -> {
          if (add(nextNode.mask, nextNode.n)) {
            // System.out.printf("[%d] [%2d] %s\n", size() - 1, nextNode.n, grid.filterStr(nextNode.mask));
            q.offer(nextNode);
          }
        });
      }
    }

    public SudokuMask largestNonOverlapping(SudokuMask mask) {
      for (int i = length - 1; i > 0; i--) {
        Set<SudokuMask> set = list.get(i);
        if (!set.isEmpty()) {
          for (SudokuMask item : set) {
            if (!mask.intersects(item)) {
              return new SudokuMask(item);
            }
          }
        }
      }
      return null;
    }

    public int minToSatisfy(SudokuMask mask) {
      // int bc = mask.bitCount();
      for (int i = length - 1; i > 0; i--) {
        Set<SudokuMask> set = list.get(i);
        if (!set.isEmpty()) {
          for (SudokuMask item : set) {
            if (!mask.intersects(item)) {
              return i;
            }
          }
        }
      }
      return 0;
    }
  }

  public static class SetArray<T> {
    List<Set<T>> list;
    int length;
    long size;

    public SetArray(int length) {
      this.length = length;
      this.size = 0;
      this.list = new ArrayList<>();
      for (int i = 0; i < length; i++) {
        list.add(new HashSet<>());
      }
    }

    public long size() {
      return size;
    }

    public boolean contains(T data, int index) {
      validateIndex(index);
      return list.get(index).contains(data);
    }

    public boolean add(T data, int index) {
      validateIndex(index);
      if (list.get(index).add(data)) {
        size++;
        return true;
      }
      return false;
    }

    private void validateIndex(int index) {
      if (index < 0 || index >= length) {
        throw new IllegalArgumentException("Bad index (" + index + "). Max " + length + ".");
      }
    }
  }

  static class DjNode {
    // The number of UAs this mask contains
    int n;
    SudokuMask mask;
    DjNode() {
      this(0, new SudokuMask());
    }
    DjNode(DjNode other) {
      this(other.n, new SudokuMask(other.mask));
    }
    DjNode(int n, SudokuMask mask) {
      this.n = n;
      this.mask = mask;
    }
    List<DjNode> nexts(List<SudokuMask> maskPool) {
      List<DjNode> nexts = new ArrayList<>();
      for (SudokuMask maybeNext : maskPool) {
        if (!mask.intersects(maybeNext)) {
          nexts.add(new DjNode(n + 1, new SudokuMask(mask).add(maybeNext)));
        }
      }
      return nexts;
    }
    void nexts(Collection<SudokuMask> maskPool, Consumer<DjNode> callback) {
      for (SudokuMask maybeNext : maskPool) {
        if (!mask.intersects(maybeNext)) {
          callback.accept(new DjNode(n + 1, new SudokuMask(mask).add(maybeNext)));
        }
      }
    }
  }

  // TODO do something with this -- maybe create a class that compares performance of various sudoku algorithms,
  // compiles the results, shows them formatted.
  private static void compareCountSolutions(ArgsMap args) {
    Map<String,Integer> puzzleStrs = new HashMap<>() {{
        put("...45.7...5........4......3.8...3.1.9..241.....69...3.2......7.3...7..........3..", 76293);
        put("....5..89......16......1..2...6.3..............1..5..45...6..73.......4..74..89.1", 50035);
        put("..3.5.7.9..7..8.4...8..............6.8...54.2...8..........932.3.42..6......3.1..", 25339);
        put("12..5..8..7.3.9........7..6...56........4.8......92..1....2...8.6.1.......8......", 300986);
        put("1..45...96...1......7...1..3........9....531.......6...9.16.......3.4.6.2...7...1", 33887);
        put(".2..56...8..3..56...........1.2...........64.....9..239.........81.2....26..314..", 7585);
        put(".2.4.6..99...........79..3.........1..9...3.........5.3.8....72...5......65.29..4", 21421);
        put("....5..89....3.......2...........9..2......75..9.8.6.2.51...8.6....9...1.92..1.57", 7535);
        put("....5.7..56...8.4...9.7.......6.....65...94.8..4....2.4.....836.3...7............", 15322);
        put("....56...76....52..95.2...3.......7.2.78...455...9.1...3.....5...8...3.......5...", 2132);
        put("..3.....9.7....65...9.71.....1..78..9....2......54.......9..3............4.1.....", 50920);
        put("...4..7......1...6.........3.....8..7...4......8.3..6.5...7....43...51.897.1...3.", 60419);
        put("1.......9.......4...4...2....2.....8......4.14.8....9..365...1.8.....5.6..56.8...", 39620);
        put("...45.................8..1..1...4...63.........8...195...7..8.1.5..9.3.48.16...5.", 6508);
        put(".....6..9........2.84.97....1...23...9......62...61.4.3...2........38..4.....4...", 64015);
        put("..3..6........8..69.67..1..5.....96.8.9.......67....1....8.....4.8...6......94..3", 68182);
        put("..3.5.7.....2......4891..6.....3.........5.......8...........252.5.....1.795.....", 240665);
        put("1..4..7.9......3...75.8.6...........8.43...6......4...2..1....6..8.........9.5..2", 39901);
        put("...4..7.9...7.8....681...4............6......931.4.....8.2.4...2...6..7....3...9.", 20840);
        put(".2.......9.6.175..........34.....961.....5....7.9.........42...........5....3..2.", 121787);
    }};

    // Total times accrued by each function
    // 0 - countSolutions
    // 1 - countSolutionsAsync (newer)
    // 2 - countSolutionsAsync (older)
    long[][] times = new long[puzzleStrs.size()][3];
    int i = 0;
    int numThreads = 8;

    System.out.println("⚠️ count2 algo (SolutionCountResult) has been deleted.\nThe performance between the two was comparable, but the implementation of count1 was much simpler, and so it stays.");
    System.out.printf("%9s%9s%9s %s\n%s\n", "count1", "count2", "count3", "puzzle", "-".repeat(81+28));
    for (Entry<String,Integer> entry : puzzleStrs.entrySet()) {
      String pStr = entry.getKey();
      int expectedCount = entry.getValue();
      long actualCount, start, end;

      start = System.currentTimeMillis();
      actualCount = new Sudoku(pStr).countSolutions();
      end = System.currentTimeMillis();
      if (actualCount == expectedCount) {
        times[i][0] = end - start;
        System.out.printf("%9d", times[i][0]);
      } else {
        System.out.printf("\n❌ countSolutions mismatch (expected %d, got %d)\n%s\n", expectedCount, actualCount, pStr);
      }

      start = System.currentTimeMillis();
      actualCount = new Sudoku(pStr).countSolutionsAsync(numThreads);
      end = System.currentTimeMillis();
      if (actualCount == expectedCount) {
        times[i][1] = end - start;
        System.out.printf("%9d", times[i][1]);
      } else {
        System.out.printf("\n❌ countSolutionsAsync1 mismatch (expected %d, got %d)\n%s\n", expectedCount, actualCount, pStr);
      }

      start = System.currentTimeMillis();
      // SolutionCountResult countResult = new Sudoku(pStr).countSolutionsAsync(numThreads, 1L, TimeUnit.MINUTES);
      // try {
      //   countResult.await();
      // } catch (InterruptedException e) {
      //   System.out.printf("\n❌ countSolutionsAsync2 failed\n%s\n", pStr);
      //   e.printStackTrace();
      // }
      // actualCount = countResult.get();
      end = System.currentTimeMillis();
      if (actualCount == expectedCount) {
        times[i][2] = end - start;
        System.out.printf("%9d", times[i][2]);
      } else {
        System.out.printf("\n❌ countSolutionsAsync2 mismatch (expected %d, got %d)\n%s\n", expectedCount, actualCount, pStr);
      }

      i++;
      System.out.println(" " + pStr);
    }
  }
}
