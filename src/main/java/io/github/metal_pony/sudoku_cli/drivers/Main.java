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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.github.metal_pony.sudoku.SieveSearcher;
import static io.github.metal_pony.sudoku.Constants.*;
import io.github.metal_pony.sudoku.Sudoku;
import io.github.metal_pony.sudoku.SudokuMask;
import io.github.metal_pony.sudoku.SudokuSieve;
import io.github.metal_pony.sudoku.util.Counting;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ScopeType;

@Command(
  name = "sudoku",
  mixinStandardHelpOptions = true,
  subcommands = CommandLine.HelpCommand.class
)
public class Main {
  @Spec
  static CommandSpec spec;

  @Option(
    names = {"-v", "--v", "--verbose"},
    scope = ScopeType.INHERIT,
    description = "Show detailed output."
  )
  boolean verbose;

  @Option(
    names = "--threads",
    arity = "0..1",
    defaultValue = "1",
    fallbackValue = "max",
    paramLabel = "N",
    description = "Threads to use; without a value, use all available processors.",
    converter = ThreadCountConverter.class
  )
  int threads;

  public static class ThreadCountConverter implements CommandLine.ITypeConverter<Integer> {
    @Override
    public Integer convert(String value) {
      if ("max".equalsIgnoreCase(value)) {
        return Runtime.getRuntime().availableProcessors();
      }

      int threads = Integer.parseInt(value);
      if (threads < 1) {
        throw new CommandLine.TypeConversionException("thread count must be at least 1");
      }
      return threads;
    }
  }

  /**
   * Contains lines read in from stdin.
   */
  List<String> linesIn = new ArrayList<>();

  @Command(
    name = "generateSolutions",
    aliases = {"solution", "solutions", "generateConfigs", "config", "configs"},
    description = "Generate Sudoku solution grids."
  )
  int generateSolutions(
    @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "1",
      paramLabel = "N",
      description = "Number of solutions to generate"
    )
    int amount,

    @Option(names = "--normalize")
    boolean normalize
  ) {
    if (amount < 1) {
      throw new CommandLine.ParameterException(
        spec.commandLine(), "N must be at least 1"
      );
    }

    int realThreads = Math.min(threads, amount);
    List<Runnable> batch = new ArrayList<>(realThreads);
    int amountPerThread = amount / realThreads;
    int remainder = amount % realThreads;
    String lineSeparator = System.lineSeparator();

    for (int t = 0; t < realThreads; t++) {
      final int amountToGen = (t == realThreads - 1) ? (amountPerThread + remainder) : amountPerThread;
      final int bufCapacity = 1<<8;
      Sudoku config = new Sudoku();
      batch.add(() -> {
        int bufSize = 0;
        // Each thread will have an output buffer in the form of a StringBuilder,
        // concatenating up to `bufCapacity` configs and newlines before writing to stdout.
        StringBuilder strb = new StringBuilder();
        for (int n = 0; n < amountToGen; n++) {
          config.genConfig();
          if (normalize) config.normalize();
          strb.append(config.toString());
          strb.append(lineSeparator);
          bufSize++;
          if (bufSize == bufCapacity) {
            print(strb.toString());
            bufSize = 0;
            strb.delete(0, strb.length());
          }
        }
        if (strb.length() > 0) {
          print(strb.toString());
        }
      });
    }

    long start = timeMs();
    runBatchAndBlock(batch, realThreads);
    long end = timeMs();
    long ms = end - start;
    double secs = ms / 1000.0;
    double gridsPerSec = secs > 0.0 ? amount / secs : 0.0;
    if (verbose) printf("-- %d ms (%.2f grids/second) --\n", ms, gridsPerSec);

    return CommandLine.ExitCode.OK;
  }

  /**
   * Validates --clues argument.
   */
  public static class ClueCountConverter implements CommandLine.ITypeConverter<Integer> {
    @Override
    public Integer convert(String value) {
      final int clues;
      try {
        clues = Integer.parseInt(value);
      } catch (NumberFormatException e) {
        throw new CommandLine.TypeConversionException(String.format(
          "clues must be an integer between %d and %d", MIN_CLUES, SPACES
        )
        );
      }

      if (clues < MIN_CLUES || clues > SPACES) {
        throw new CommandLine.TypeConversionException(String.format(
          "clues must be between %d and %d", MIN_CLUES, SPACES
        ));
      }
      return clues;
    }
  }

  /**
   * Used for generatePuzzles options mutual exclusivity.
   * For example, `--grid 12345etc --mask 010011etc` does not make sense.
   * Same with --mask + --clues.
   * Same with --mask + --pali.
   * However, any combo of --grid --pali --clues is permissible.
   */
  public static class PuzzleInputOptions {
    @ArgGroup(exclusive = true, multiplicity = "0..1")
    SourceOptions source;
  }
  public static class SourceOptions {
    @Option(names = {"--mask", "-m"}, paramLabel = "MASK")
    String mask;

    @ArgGroup(exclusive = false, multiplicity = "0..1")
    PuzzleOptions puzzle;
  }

  /**
   * Subset of puzzle generation options that are not mutually exclusive.
   */
  public static class PuzzleOptions {
    @Option(names = "--clues", defaultValue = "27", converter = ClueCountConverter.class)
    int clues = 27;

    String solution;
    @Option(
      names = {"--solution", "--grid"},
      paramLabel = "SOLUTION",
      description = "Use a specific solution."
    )
    // Validates the --grid argument.
    public void setSolution(String value) {
      if (!Sudoku.isValidStr(value)) {
        throw new CommandLine.ParameterException(spec.commandLine(),
          String.format("ERROR: Malformed sudoku string: %s", value)
        );
      }

      if (!new Sudoku(value).isSolved()) {
        throw new CommandLine.ParameterException(spec.commandLine(),
          String.format("ERROR: Sudoku solution given is invalid or not solved: %s", value)
        );
      }

      solution = value;
    }

    @Option(names = {"--palindrome", "--pali"})
    boolean palindrome;
  }

  private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();

  /** Lines read from stdin.*/
  private static final List<String> lines = new ArrayList<>();

  static final ArgsMap args = new ArgsMap();

  private static final Map<String, Runnable> COMMANDS = new HashMap<>() {{
    put("generatePaliMasks", Main::generatePaliMasks);
    put("paliSearch", Main::paliSearch);

    put("check", Main::check);
    put("solve", Main::solve);
    put("scramble", Main::scramble);
    put("sieve", Main::createSieve);
    put("fingerprint", Main::fingerprint);
    put("fp", Main::fingerprint);
    put("sieveSearch", Main::sieveSearch);
    put("minSearch", Main::minSearch);

    // TODO https://github.com/metal-pony/sudoku-cli/issues/7
    // put("transform", Main::transform);

    // --puzzle %s --threads %d --timeout %d
    // For testing / experimentation
    put("benchy", Main::benchy);
    put("generateBands", GenerateInitialBands::generateInitialBands);
    put("adhoc", Main::adhoc);
    put("adhoc2", Main::adhoc2);
    put("wip1", Main::wip_1); // broken until Sudoku.searchForPuzzlesAsync is fixed
    put("wip3", Main::wip3);
  }};

  /**
   * Generates Sudoku puzzles.
   * Can read solution grids from stdin, one per line. When supplied, generates
   * {amount} puzzles for each solution.
   * <pre>
   * Options/Defaults:
   *   N {n >= 1}               (1). Number to generate.
   *   --clues {17 <= n <= 81}  (27). Number of clues on puzzles.
   *   --grid {str}             (random solution for each puzzle generated). Generate puzzles with a specific solution.
   *   --normalize              (false). Reorder grid values such that the top row is ascending.
   *   --pretty                 (false). Print puzzles in multi-line format.
   *   --pali | --palindrome    (false). Create a puzzle with palindrome symmetry.
   *   --mask | -m {str}        Generate puzzles with clues as indicated in the mask.
   * </pre>
   */
  @Command(
    name = "generatePuzzles",
    aliases = {"puzzle", "puzzles"},
    description = "Generate Sudoku puzzles."
  )
  int generatePuzzles(
    @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "1",
      paramLabel = "N",
      description = "Number of puzzles to generate"
    )
    int amount,

    @Mixin
    PuzzleInputOptions inputOptions,

    @Option(names = "--normalize")
    boolean normalize,

    @Option(names = "--pretty")
    boolean prettyPrint
  ) {
    PuzzleOptions puzzleOptions = inputOptions.source == null
      ? null
      : inputOptions.source.puzzle;
    int clues = puzzleOptions == null ? 27 : puzzleOptions.clues;
    String solution = puzzleOptions == null ? null : puzzleOptions.solution;
    boolean solutionGiven = (solution != null);
    boolean palindrome = puzzleOptions != null && puzzleOptions.palindrome;
    String mask = inputOptions.source == null ? null : inputOptions.source.mask;

    // This command can read from stdin.
    // Any data read is expected to be a solved solution grid (1 per line).
    // Used as the solution(s) for generated puzzles.
    // If amount {N} is specified, then N puzzles are generated for each solution.
    // If --grid is also given, it will be processed first before any stdin solutions.
    if (solutionGiven) {
      linesIn.addFirst(solution);
    }

    // Mask validation:
    // - minimum number of clues
    // - satisfy prime sieve
    if (mask != null) {
      SudokuMask m = new SudokuMask(mask);
      if (!SudokuMask.satisfiesPrimeSieve(m)) {
        throw new RuntimeException("ERROR: Specified mask does not satisfy the prime sieve.");
      }
      if (m.bitCount() < MIN_CLUES) {
        throw new RuntimeException("ERROR: Specified mask too few clues.");
      }
    }

    if (verbose) printf("Using %d threads.\n", threads);

    if (linesIn.isEmpty()) {
      if (verbose) printf(
        "Generating %d %d-clue puzzles from random solutions.\n",
        amount, clues
      );

      Runnable generatePuzzle = () -> {
        try {
          Sudoku puzzle = null;
          if (palindrome) {
            if (verbose) println("palindrome generation...");
            Sudoku _solution = solutionGiven ? new Sudoku(solution) : null;
            // TODO requires io.github.metal-pony:sudoku:0.1.1-SNAPSHOT
            puzzle = Sudoku.generatePalindromePuzzle(clues, _solution, null);
          } else if (mask != null) {
            SudokuMask m = new SudokuMask(mask);
            // TODO create iterable form for searchForPuzzles
            AtomicReference<Sudoku> _p = new AtomicReference<>();
            Sudoku.searchForPuzzles(m, p -> {
              _p.set(p);
              return false;
            });
            puzzle = _p.get();
          } else {
            puzzle = Sudoku.generatePuzzle(clues);
          }
          if (normalize) puzzle.normalize();
          println(prettyPrint ? puzzle.toMedString() : puzzle.toString());
        } catch (Exception ex) {
          // TODO remove try/catch after debugging
          ex.printStackTrace();
        }
      };

      long start = timeMs();
      repeatThreadedAndBlock(generatePuzzle, amount, threads);
      long end = timeMs();
      vPrintf("-- %d ms --\n", end - start);
      return CommandLine.ExitCode.OK;
    }

    // ELSE: Multiple grids given as input.
    // Generate {amount} puzzles for each solution grid.
    if (verbose) printf(
      "Generating %d %d-clue puzzles for each given solutions (%d).\n",
      amount, clues, linesIn.size()
    );

    for (String solutionStr : linesIn) {
      try {
        final Sudoku _solution = new Sudoku(solutionStr);
        if (!_solution.isValid()) {
          throw new CommandLine.ParameterException(spec.commandLine(), String.format(
            "WARNING: Skipped %s: Invalid solution grid",
            solutionStr
          ));
        }
        if (!_solution.isSolved()) {
          throw new CommandLine.ParameterException(spec.commandLine(), String.format(
            "WARNING: Skipped %s: Solution grid is not solved",
            solutionStr
          ));
        }
        final SudokuSieve sieve = new SudokuSieve(_solution);
        sieve.seed();
        Runnable generatePuzzle = () -> {
          try {
            Sudoku puzzle = null;
            if (palindrome) {
              puzzle = Sudoku.generatePalindromePuzzle(clues, _solution, sieve);
            } else if (mask != null) {
              SudokuMask m = new SudokuMask(mask);
              AtomicReference<Sudoku> _p = new AtomicReference<>();
              Sudoku.searchForPuzzles(m, p -> {
                _p.set(p);
                return false;
              });

              puzzle = _p.get();
            } else {
              puzzle = Sudoku.generatePuzzle(_solution, clues, sieve, 0, 60*1000L);
            }
            if (normalize) puzzle.normalize();
            println(prettyPrint ? puzzle.toMedString() : puzzle.toString());
          } catch (Exception ex) {
            // TODO remove try/catch after debugging
            ex.printStackTrace();
          }
        };

        if (verbose) printf("Solution:\n%s\nPuzzles:\n", prettyPrint ? _solution.toMedString() : _solution.toString());
        long start = timeMs();
        repeatThreadedAndBlock(generatePuzzle, amount, threads);
        long end = timeMs();
        if (verbose) printf("-- %d ms --\n", end - start);
      } catch (Exception ex) {
        System.err.println("ERROR: Specified grid is malformed.");
        return CommandLine.ExitCode.SOFTWARE;
      }
    }

    return CommandLine.ExitCode.OK;
  }

  /**
   * Generates a given amount of palindrome puzzle masks.
   */
  private static void generatePaliMasks() {
    final int threads = args.threads();
    final int amount = args.amountOrDefault(1);
    final int clues = args.cluesOrDefault(27);

    vPrintf("Using %d threads.\n", threads);

    if (lines.isEmpty()) {
      // Generate {amount} puzzles, each from random solutions.
      vPrintf(
        "Generating %d %d-clue puzzles from random solutions.\n",
        amount, clues
      );

      Runnable generatePuzzle = () -> {
        SudokuMask mask = new SudokuMask();
        mask.randomPalindrome(clues);
        println(mask.toStringDots());
      };

      long start = timeMs();
      repeatThreadedAndBlock(generatePuzzle, amount, threads);
      long end = timeMs();
      vPrintf("Done generating in %d ms.\n", end - start);
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

    vPrintf("Using %d threads.\n", threads);

    if (lines.isEmpty()) {
      // Generate {amount} puzzles, each from random solutions.
      vPrintf(
        "Generating %d %d-clue puzzles from random solutions.\n",
        amount, clues
      );

      Runnable generatePuzzle = () -> {
        Sudoku puzzle = Sudoku.generatePalindromePuzzle(clues);
        if (normalize) puzzle.normalize();

        if (args.isVerbose()) {
          if (prettyPrint) {
            printf("[%d] %s\n%s\n\n", puzzle.numClues(), puzzle.toString(), puzzle.toMedString());
          } else {
            printf("[%d] %s\n", puzzle.numClues(), puzzle.toString());
          }
        } else {
          println(prettyPrint ? puzzle.toMedString() : puzzle.toString());
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
      vPrintf("Done generating in %d ms.\n", end - start);
      return;
    }

    // ELSE: Multiple grids given as input.
    // TODO Generate {amount} puzzles for each grid (given as the solutions).

    vPrintf(
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
    vPrintln("Seeding sieve...");
    List<Runnable> workList = new ArrayList<>();
    sieve.fullPrintCombos(4).forEach(mask -> {
      workList.add(() -> { sieve.searchForUAs(mask); });
    });
    runBatchAndBlock(workList, threads);
    vPrintln(sieve.toString());
    final int sieveSizeAtStart = sieve.size();
    vPrintf("Done. Sieve has %d items.\n", sieveSizeAtStart);
    vPrintln("Starting sequential search...");

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
          vPrintf("[%d] %s {r %d}\n", puzzles, p.toString(), r);
        } else {
          System.out.println(p.toString());
        }
        puzzles++;
      }
    }

    final long endTimeMs = System.currentTimeMillis();

    vPrintf(
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

  /**
   * Checks grid(s) string validity, then outputs various stats as JSON including
   * grid, isValid, numClues, isSolved, solutionsFlag,
   * difficulty rating (NYI, only if solutionsFlag == 1),
   * solve path (NYI, only if solutionsFlag == 1),
   * solutions (auto if solutionsFlag == 1, otherwise option),
   * solutionCount (option),
   * fingerprint (option, only if solutionsFlag == 1),
   * sieve (option).
   *
   * If grid is malformed, sets { error: "malformed" }.
   *
   * ERROR if no grid(s) given.
   *
   * Example
   * > check --grid '.....8..98...16.7.94......8.92..186.46..........56.....3...958.28.3.4..6.1....42.' \
   *   --fp 'dc2,dc3'
   *   {
   *     "grid": ".....8..98...16.7.94......8.92..186.46..........56.....3...958.28.3.4..6.1....42.",
   *     "isValid": true,
   *     "numClues": 30,
   *     "isSolved": false,
   *     "solutionsFlag": 1,
   *     "solutions": [
   *       "721438659853916274946275318392741865465892731178563942634129587287354196519687423"
   *     ],
   *   }
   */
  private static void check() {
    final boolean count = args.containsKey("count");
    final boolean all = args.containsKey("all");
    final int threads = args.threads();
    final boolean dc2 = args.containsKey("dc2");
    final boolean dc3 = args.containsKey("dc3");
    final boolean dc4 = args.containsKey("dc4");
    final boolean fp2 = args.containsKey("fp2");
    final boolean fp3 = args.containsKey("fp3");
    final boolean fp4 = args.containsKey("fp4");
    boolean useSieve = args.containsKey("sieve");
    final String sieveAlgo = args.get("sieve");
    if (sieveAlgo == null) useSieve = false;

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
      Map<String,Object> result = new HashMap<>();
      Sudoku grid = null;

      if (Sudoku.isValidStr(gridStr)) {
        grid = new Sudoku(gridStr);
        result.put("grid", grid.toString());
        result.put("isValid", grid.isValid());
        result.put("isSolved", grid.isSolved());
        result.put("numClues", grid.numClues());
        int flag = grid.solutionsFlag();
        result.put("solutionsFlag", flag);
        List<String> solutionStrs = new ArrayList<>();
        long solutionCount = 0L;
        if (flag == 1 || all) {
          solutionStrs = grid.getAllSolutions().stream().map(s -> s.toString()).toList();
          result.put("solutions", solutionStrs);
          solutionCount = solutionStrs.size();
        } else if (count) {
          solutionCount = grid.countSolutionsAsync(threads);
        }
        if (count) {
          result.put("solutionCount", solutionCount);
        }
        if (flag == 1) {
          Sudoku solution = new Sudoku(solutionStrs.getFirst());
          if (dc2) result.put("fingerprint_dc2", solution.dc2());
          if (dc3) result.put("fingerprint_dc3", solution.dc3());
          if (dc4) result.put("fingerprint_dc4", solution.dc4());
          if (fp2) result.put("fingerprint_fp2", solution.fp2());
          if (fp3) result.put("fingerprint_fp3", solution.fp3());
          if (fp4) result.put("fingerprint_fp4", solution.fp4());
          if (useSieve) {
            SudokuSieve sieve = new SudokuSieve(solution);
            List<SudokuMask> seedMasks = new ArrayList<>();
            switch (sieveAlgo) {
              case "dc2":
                seedMasks.addAll(sieve.digitCombos(2));
                break;
              case "dc3":
                seedMasks.addAll(sieve.digitCombos(3));
                break;
              case "dc4":
                seedMasks.addAll(sieve.digitCombos(4));
                break;
              case "fp2":
                seedMasks.addAll(sieve.fullPrintCombos(2));
                break;
              case "fp3":
                seedMasks.addAll(sieve.fullPrintCombos(3));
                break;
              case "fp4":
                seedMasks.addAll(sieve.fullPrintCombos(4));
                break;
              default:
                throw new RuntimeException(
                  String.format("unrecognized sieve seeding algo %s", sieveAlgo)
                );
            }
            List<Runnable> seedWork = new ArrayList<>();
            seedMasks.forEach(mask -> {
              seedWork.add(() -> {
                sieve.searchForUAs(mask);
              });
            });
            vPrintf("Seeding sieve... ");
            long start = timeMs();
            runBatchAndBlock(seedWork, threads);
            long end = timeMs();
            vPrintf("Done (%d ms).\n", (end - start));
            result.put("sieve", sieve.items(new ArrayList<>()));
          }
        } else if (dc2 || dc3 || dc4 || fp2 || fp3 || fp4) {
          printf("WARNING: One of more fingerprint args were specified but grid has multiple solutions.\n");
        }
      } else {
        if (gridStr.length() > SPACES) {
          gridStr = gridStr.substring(0, SPACES) + "...(etc)";
        }
        result.put("grid", gridStr);
        result.put("error", "malformed");
      }

      GsonBuilder gsonBuilder = new GsonBuilder();
      gsonBuilder.setFormattingStyle(FormattingStyle.PRETTY);
      Gson gson = gsonBuilder.create();
      println(gson.toJson(result));
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
      vPrintf("Found solutions for %d puzzles in %d ms.\n", lines.size(), end-start);
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
          printf("%s -> %d solutions\n", grid.toString(), count);
        } else {
          println(count);
        }
      } else if (allSolutions) {
        grid.searchForSolutionsAsync(solution -> println(solution.toString()), threads);
      } else {
        int solutionCount = 0;
        for (Sudoku solution : grid.solutions()) {
          println(solution.toString());
          if (++solutionCount < amount) break;
        }
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
          printf("%s -> %s\n", gridStrs.get(i), solutionFtrs.get(i).get());
        } else {
          println(solutionFtrs.get(i).get());
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

      println(grid.scramble().toString());
    }
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

    vPrintf("Creating sieves for given grids, with algo %s, and %d threads.", algo, threads);

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
      List<Runnable> workList = new ArrayList<>();
      seedingMasks.forEach(mask -> {
        workList.add(() -> { sieve.searchForUAs(mask); });
      });
      runBatchAndBlock(workList, threads);
      long endTime = System.currentTimeMillis();
      println(grid.toString());
      if (showBigInt) {
        for (SudokuMask item : sieve.items(new ArrayList<>())) {
          String decimalStr = (new BigInteger(item.toString(), 2)).toString();
          System.out.printf("%30s %s\n", decimalStr, grid.filterStr(item));
        }
      } else {
        println(sieve.toString());
      }
      printf("Added %d items to sieve (%d ms).\n", sieve.size(), endTime - startTime);
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

    if (threads > 1) vPrintf("Using %d threads.\n", threads);

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
        println(fp);
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
        println(solutionFtrs.get(i).get());
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
    for (int ci = 0; ci < SPACES / 2; ci++) {
      prev1 = test.getDigit(ci);
      if (prev1 > 0) {
        prev2 = test.getDigit(SPACES - ci - 1);
        test.clearCell(ci);
        test.clearCell(SPACES - ci - 1);
        flag = test.solutionsFlag();
        if (flag == 1) {
          // verboseOutf("❌ %s\n", test.toString());
          return false;
        }
        test.setDigit(ci, prev1);
        test.setDigit(SPACES - ci - 1, prev2);
      }
    }

    return true;
  }

  // Generates random palindrome mask (loops until mask satisfies prime sieve)
  // (Sudoku.searchForPuzzlesAsync) Searches for puzzles with the mask shape
  private static void wip_1() {
    final int clues = args.cluesOrDefault(24);
    if (clues < MIN_CLUES || clues > SPACES) {
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
    // Sudoku.searchForPuzzlesAsync(paliMask, (puzzle) -> {
    //   System.out.printf("[%d] %s\n", count.incrementAndGet(), puzzle.toString());
    //   return true;
    // }, threads);
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
    vPrintln("Searching for palindrome puzzles. Solution:");
    // System.out.println("Searching for palindrome puzzles. Solution:");
    System.out.println(solution.toString());

    SudokuSieve sieve = new SudokuSieve(solution);
    vPrintln("Seeding sieve...");
    List<Runnable> workList = new ArrayList<>();
    sieve.fullPrintCombos(4).forEach(mask -> {
      workList.add(() -> { sieve.searchForUAs(mask); });
    });
    runBatchAndBlock(workList, threads);
    vPrintln(sieve.toString());
    vPrintf("Done. Sieve has %d items.\n", sieve.size());
    vPrintln("Starting sequential search...");

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
    vPrintf("Found %d palindrome puzzles (%d ms).\n", found.get(), (endTimeMs - startTimeMs));


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
    try {
      List<Sudoku> sudoku17s = io.github.metal_pony.sudoku.Main.sudoku17();
      long startSysTime = System.currentTimeMillis();
      long cpuTime = timeCpuExecution(() -> {
        sudoku17s.forEach(puzzle -> {
          puzzle.solution();
        });
      });
      long endSysTime = System.currentTimeMillis();
      return new long[]{endSysTime - startSysTime, cpuTime};
    } catch (IOException e) {
      e.printStackTrace();
      return new long[]{0L, 0L};
    }
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
      sieve.seed(level);
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
    int count = 0;
    try {
      for (Sudoku p : io.github.metal_pony.sudoku.Main.sudoku17()) {
        String pStr = p.toString();
        p.resetCandidatesAndValidity();
        p.reduce();
        if (p.isSolved()) {
          System.out.println(pStr);
          count++;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    // System.out.printf("Total %d puzzles", count.get());

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

    if (threads > 1) vPrintf("Using %d threads.\n", threads);

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR sieveSearch: Specified grid is malformed.");
        System.exit(1);
      }

      vPrintf("Searching with grid:\n%s\n", grid.toString());

      SudokuSieve sieve = new SudokuSieve(grid);
      vPrintf("Seeding sieve (algo %s; threads %d)...", algo, threads);
      long seedStartTime = timeMs();
      List<SudokuMask> seedMasks = null;
      switch (algo) {
        case "dc2": seedMasks = sieve.digitCombos(2); break;
        case "dc3": seedMasks = sieve.digitCombos(3); break;
        case "dc4": seedMasks = sieve.digitCombos(4); break;
        case "fp2": seedMasks = sieve.fullPrintCombos(2); break;
        case "fp3": seedMasks = sieve.fullPrintCombos(3); break;
        case "fp4": seedMasks = sieve.fullPrintCombos(4); break;
        default: throw new RuntimeException("Something went wrong creating fingerprint.");
      }
      List<Runnable> workList = new ArrayList<>();
      seedMasks.forEach(mask -> {
        workList.add(() -> { sieve.searchForUAs(mask); });
      });
      runBatchAndBlock(workList, threads);
      long seedEndTime = timeMs();
      vPrintf(
        "Done in %d ms.\n%s\nSieve has %d items.\n",
        seedEndTime - seedStartTime,
        sieve.toString(),
        sieve.size()
      );

      SieveSearcher searcher = new SieveSearcher(sieve);
      println("Starting in just a sec...");
      sleep(2000L);
      searcher.search(clues);
      println("Done searching.");
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

    if (threads > 1) vPrintf("Using %d threads.\n", threads);

    for (String gridStr : lines) {
      Sudoku grid = null;
      try {
        grid = new Sudoku(gridStr);
      } catch (Exception ex) {
        System.err.println("ERROR sieveSearch: Specified grid is malformed.");
        System.exit(1);
      }

      vPrintf("Searching with grid:\n%s\n", grid.toString());

      // Create and seed a sieve
      SudokuSieve sieve = new SudokuSieve(grid);
      vPrintf("Seeding sieve (algo %s; threads %d)...", algo, threads);
      long seedStartTime = timeMs();
      List<SudokuMask> seedMasks = null;
      switch (algo) {
        case "dc2": seedMasks = sieve.digitCombos(2); break;
        case "dc3": seedMasks = sieve.digitCombos(3); break;
        case "dc4": seedMasks = sieve.digitCombos(4); break;
        case "fp2": seedMasks = sieve.fullPrintCombos(2); break;
        case "fp3": seedMasks = sieve.fullPrintCombos(3); break;
        case "fp4": seedMasks = sieve.fullPrintCombos(4); break;
        default: throw new RuntimeException("Something went wrong creating fingerprint.");
      }
      List<Runnable> workList = new ArrayList<>();
      seedMasks.forEach(mask -> {
        workList.add(() -> { sieve.searchForUAs(mask); });
      });
      runBatchAndBlock(workList, threads);
      long seedEndTime = timeMs();
      vPrintf(
        "Done in %d ms.\n%s\nSieve has %d items.\n",
        seedEndTime - seedStartTime,
        sieve.toString(),
        sieve.size()
      );

      SieveSearcher searcher = new SieveSearcher(sieve);
      println("Starting in just a sec...");
      sleep(2000L);
      searcher.minsearch();
      println("Done searching.");
    }
  }

  // Reports the cpu execution time of Sudoku.generateConfig().
  private static void benchConfigGeneration() {
    final int amount = args.amountOrDefault(1);

    // TODO Adapt for multithreading
    // final int threads = args.threads();

    List<Sudoku> configs = new ArrayList<>();
    long time = timeCpuExecution(() -> {
      for (int t = 0; t < amount; t++) {
        configs.add(Sudoku.generateConfig());
      }
    });

    printf(
      "benchConfigGeneration({ amount: %d }): %d ms\n",
      amount,
      TimeUnit.NANOSECONDS.toMillis(time)
    );
  }

  /** Shorthand for System.out.print(x).*/
  private static void print(Object x) { System.out.print(x); }
  /** Shorthand for System.out.println(x).*/
  private static void println(Object x) { System.out.println(x); }
  /** Shorthand for System.out.printf(format, args).*/
  private static void printf(String format, Object...args) {
    System.out.printf(format, args);
  }
  /** Prints a line only if `--verbose` output is enabled.*/
  private static void vPrintln(Object x) {
    if (args.isVerbose()) System.out.println(x);
  }
  /** Prints only if `--verbose` output is enabled.*/
  private static void vPrintf(String format, Object...args) {
    if (Main.args.isVerbose()) System.out.printf(format, args);
  }
  /** Shorthand for System.currentTimeMillis().*/
  private static long timeMs() { return System.currentTimeMillis(); }

  /** Shorthand for Thread.sleep(timeMs). Handles error by printing stack trace.*/
  private static void sleep(long timeMs) {
    try {
      Thread.sleep(timeMs);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  /** Constrains `value` to the interval [min, max].*/
  private static int inBounds(int value, int min, int max) {
    return Math.max(min, Math.min(value, max));
  }

  /**
   * Times CPU execution of a repeated function.
   * @param runnable Function to execute.
   * @param repeat Number of times to execute.
   * @return Time (ms) the CPU spent in execution.
   */
  private static long timeCpuExecution(Runnable runnable, int repeat) {
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    long start = bean.getCurrentThreadCpuTime();
    for (int t = 0; t < repeat; t++) {
      runnable.run();
    }
    long end = bean.getCurrentThreadCpuTime();
    return TimeUnit.NANOSECONDS.toMillis(end - start);
  }

  /**
   * Times CPU execution of a function.
   * @param runnable Function to execute.
   * @return Time (ms) the CPU spent in execution.
   */
  static long timeCpuExecution(Runnable runnable) {
    return timeCpuExecution(runnable, 1);
  }

  /**
   * Reads all lines from InputStream into the given List.
   * @param inStream Stream to read from.
   * @param lines List to store content in.
   */
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

  /**
   * Creates a ThreadPool and executes `runnable` the given number of times,
   * blocking until the work is finished and the pool is terminated.
   * @param runnable Function to execute.
   * @param times Number of times to call runnable.
   * @param threads Number of threads to use.
   */
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

  /**
   * Runs a List of Runnables (the "batch"), blocking until the work is finished
   * and the pool is terminated.
   * @param batch Functions to execute.
   * @param threads Number of threads to use.
   */
  private static void runBatchAndBlock(List<Runnable> batch, int threads) {
    ThreadPoolExecutor pool = new ThreadPoolExecutor(
      threads, threads,
      1L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>()
    );
    pool.prestartAllCoreThreads();
    batch.forEach(pool::submit);
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

  private static List<String> readSysInLines(List<String> lines) {
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
    return lines;
  }

  /**
   * ArgsMap is a HashMap containing command arguments and their string values.
   * It defines a method to parse arguments passed from command line, populating
   * its key-value pairs. Also defines helper methods to get or default the most
   * commonly used arguments.
   */
  public static final class ArgsMap extends HashMap<String,String> {
    private static final String VERBOSE_ARG1 = "verbose";
    private static final String VERBOSE_ARG2 = "v";
    private static final Set<String> ALGOS = new HashSet<>() {{
      add("dc2"); add("dc3"); add("dc4");
      add("fp2"); add("fp3"); add("fp4");
    }};

    /**
     * Parses the given commandline arguments. Populates its key-value mappings
     * with string values. Option names and values should be separated by a space;
     * equals sign separation is not supported.
     * Keys start with `--`.
     * Some arguments may be key-only, such as `--verbose`, when the value is boolean.
     * Boolean options should always default to false.
     * @param args Array of string arguments for parsing.
     * @param firstArgIndex Index indicating where to start parsing options.
     */
    public void parseCommandLineArgs(String[] args, int firstArgIndex) {
      if (args != null && args.length > firstArgIndex) {
        String lastArgKey = null;
        for (int i = firstArgIndex; i < args.length; i++) {
          String arg = args[i];

          // Args can be in the form `--key value`,
          // or (for things like boolean flags)  plainly `--key`.

          // TODO https://github.com/metal-pony/sudoku-cli/issues/3

          // If key -> add it to the map with an empty value.
          // Fails if the key contains non-alphabet chars.
          // Else (is value) -> pair it with the last key seen.
          // Fails if there has not been a key yet.
          if (arg.startsWith("--")) {
            lastArgKey = arg.substring(2);
            if (!lastArgKey.matches("[a-zA-Z0-9]+")) {
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

  public static void main(String[] args) {
    Main main = new Main();
    readSysInLines(main.linesIn);
    int exitCode = new CommandLine(main).execute(args);
    System.exit(exitCode);
  }
}
