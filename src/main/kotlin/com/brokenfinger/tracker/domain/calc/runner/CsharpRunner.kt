package com.brokenfinger.tracker.domain.calc.runner

import com.brokenfinger.tracker.domain.ProblemExample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * `(user's code, measured examples) → runner_test.cs` plus `runner_test.csproj`, or a
 * refusal (#88).
 *
 * Seventh and last language in the measured order, and the first whose runner is **two
 * artifacts**: C# compiles projects, not files, so a project file rides along as an
 * [Runner.ExtraFile]. The csproj compiles exactly `Solution.cs` and `runner_test.cs` —
 * default globbing is disabled because it would swallow every `.cs` under `attempts/`,
 * each declaring another `Solution` — and pins `<StartupObject>` to the harness, because
 * the measured main-style skeleton has a `Main` of its own (on a class named `Example`,
 * not `Solution`) and two entry points are otherwise a compile error.
 *
 * The measured main-style skeleton also ships a `Console.Clear()` — which targets the real
 * console, not the swapped-in writers — so whether it misbehaves under redirection is a
 * per-OS question the execution suite exists to measure.
 */
object CsharpRunner {
    fun generate(code: String, examples: List<ProblemExample>): Runner {
        if (examples.isEmpty()) {
            return Runner.Refused("no examples were captured — press Run Code (코드 실행) once and they will be")
        }
        return when (ProblemShape.ofCsharp(code)) {
            ProblemShape.SOLUTION_FUNCTION -> solutionRunner(code, examples)
            ProblemShape.STDIN_MAIN -> stdinRunner(code, examples)
            ProblemShape.UNRECOGNISED -> Runner.Refused("the solution matches neither measured shape (protocol §7.1)")
        }
    }

    // Solution-style ------------------------------------------------------------------------

    private fun solutionRunner(code: String, examples: List<ProblemExample>): Runner {
        val signature = CsharpSignature.of(code)
            ?: return Runner.Refused(
                "the solution signature could not be read — supported types are int, long, " +
                    "double, bool, string, their arrays, and the rectangular int[,]",
            )
        val calls = examples.mapIndexed { index, example ->
            callFor(index, example, signature) ?: return refusalFor(index, example, signature)
        }
        return generated(solutionSource(calls.joinToString("\n")))
    }

    private fun callFor(index: Int, example: ProblemExample, signature: CsharpSignature): String? {
        val raw = example.input ?: return null
        val values = ExampleValues.arguments(raw) ?: return null
        if (values.size != signature.parameters.size) return null
        val arguments = values.zip(signature.parameters).map { (value, parameter) ->
            literalOf(value, parameter.type) ?: return null
        }
        val expected = example.expected?.let { ExampleValues.single(it) }
            ?.let { literalOf(it, signature.returnType) }
        return "        Check(${index + 1}, new Solution().solution(${arguments.joinToString(", ")}), " +
            "${expected ?: "null /* expected value not captured */"});"
    }

    private fun refusalFor(index: Int, example: ProblemExample, signature: CsharpSignature): Runner.Refused {
        val values = example.input?.let { ExampleValues.arguments(it) }
        val detail = when {
            values == null -> "example ${index + 1} could not be parsed as an argument list"
            values.size != signature.parameters.size ->
                "example ${index + 1} has ${values.size} argument(s) but solution declares ${signature.parameters.size}"
            else -> "example ${index + 1} does not fit the declared parameter types"
        }
        return Runner.Refused("$detail — refusing rather than generating a runner that tests the wrong thing")
    }

    /**
     * A JSON value as a C# literal **of the declared type** — the declaration decides, the
     * value only has to fit it. Null when it does not fit; the caller refuses.
     */
    private fun literalOf(value: JsonElement, type: String): String? = when {
        type == "int[,]" -> gridLiteral(value)
        type.endsWith("[]") -> arrayLiteral(value, type.removeSuffix("[]"))
        else -> scalarLiteral(value, type)
    }

    private fun arrayLiteral(value: JsonElement, elementType: String): String? {
        val array = value as? JsonArray ?: return null
        val elements = array.map { scalarLiteral(it, elementType) ?: return null }
        return "new $elementType[] {${elements.joinToString(", ")}}"
    }

    /** `new int[,] {{1, 2}, {3, 4}}` — rectangular by construction, so a jagged value refuses. */
    private fun gridLiteral(value: JsonElement): String? {
        val rows = (value as? JsonArray)?.map { it as? JsonArray ?: return null } ?: return null
        if (rows.isEmpty()) return null
        val cols = rows.first().size
        if (cols == 0 || rows.any { it.size != cols }) return null
        val rowLiterals = rows.map { row ->
            val elements = row.map { scalarLiteral(it, "int") ?: return null }
            "{${elements.joinToString(", ")}}"
        }
        return "new int[,] {${rowLiterals.joinToString(", ")}}"
    }

    private fun scalarLiteral(value: JsonElement, type: String): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return when (type) {
            "int" -> primitive.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toString()
            "long" -> primitive.longOrNull?.let { "${it}L" }
            "double" -> primitive.doubleOrNull?.toString()
            "bool" -> primitive.booleanOrNull?.toString()
            "string" -> if (primitive.isString) quoted(primitive.content) else null
            else -> null
        }
    }

    private fun quoted(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""

    private fun solutionSource(cases: String): String = """
$HEADER
using System;
using System.Linq;

public static class RunnerTest
{
    private static int failures = 0;

    public static void Main()
    {
$cases
        Console.WriteLine(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) Environment.Exit(1);
    }

    private static void Check(int index, object actual, object expected)
    {
        bool ok = Same(actual, expected);
        string detail = "  FAIL — expected " + Stringify(expected) + ", got " + Stringify(actual);
        Console.WriteLine("example " + index + (ok ? "  PASS" : detail));
        if (!ok) failures += 1;
    }

    // Arrays compare by reference under Equals; the judge compares by content.
    private static bool Same(object a, object b)
    {
        if (a is int[,] ga && b is int[,] gb)
        {
            return ga.GetLength(0) == gb.GetLength(0) && ga.GetLength(1) == gb.GetLength(1)
                && ga.Cast<int>().SequenceEqual(gb.Cast<int>());
        }
        if (a is int[] ia && b is int[] ib) return ia.SequenceEqual(ib);
        if (a is long[] la && b is long[] lb) return la.SequenceEqual(lb);
        if (a is double[] da && b is double[] db) return da.SequenceEqual(db);
        if (a is bool[] xa && b is bool[] xb) return xa.SequenceEqual(xb);
        if (a is string[] sa && b is string[] sb) return sa.SequenceEqual(sb);
        return Equals(a, b);
    }

    private static string Stringify(object value)
    {
        if (value == null) return "null";
        if (value is int[,] grid)
        {
            var rows = new System.Collections.Generic.List<string>();
            for (int r = 0; r < grid.GetLength(0); r++)
            {
                var row = new System.Collections.Generic.List<string>();
                for (int c = 0; c < grid.GetLength(1); c++) row.Add(grid[r, c].ToString());
                rows.Add("[" + string.Join(", ", row) + "]");
            }
            return "[" + string.Join(", ", rows) + "]";
        }
        if (value is Array array) return "[" + string.Join(", ", array.Cast<object>()) + "]";
        return value.ToString();
    }
}
    """.trimIndent() + "\n"

    // Main-style ----------------------------------------------------------------------------

    private val MAIN_NO_ARGS = Regex("""static\s+void\s+Main\s*\(\s*\)""")
    private val MAIN_WITH_ARGS = Regex("""static\s+void\s+Main\s*\(\s*string\[\]\s+\w+\s*\)""")
    private val CLASS_NAME = Regex("""class\s+(\w+)""")

    private fun stdinRunner(code: String, examples: List<ProblemExample>): Runner {
        val call = when {
            MAIN_NO_ARGS.containsMatchIn(code) -> "Main()"
            MAIN_WITH_ARGS.containsMatchIn(code) -> "Main(new string[0])"
            else -> return Runner.Refused(
                "Main's signature is unmeasured — the skeleton declares public static void Main()",
            )
        }
        // The measured skeleton names its class Example, but the class is whichever one
        // holds Main — found as the nearest declaration before it.
        val mainAt = Regex("""static\s+void\s+Main""").find(code)?.range?.first
            ?: return Runner.Refused("Main could not be located")
        val holder = CLASS_NAME.findAll(code).lastOrNull { it.range.first < mainAt }?.groupValues?.get(1)
            ?: return Runner.Refused("the class holding Main could not be read")
        val cases = examples.mapIndexed { index, example ->
            val stdin = (example.input?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s stdin could not be read as a quoted text")
            val expected = (example.expected?.let { ExampleValues.single(it) } as? JsonPrimitive)?.contentOrNull
                ?: return Runner.Refused("example ${index + 1}'s expected output could not be read as a quoted text")
            "        Check(${index + 1}, ${quoted(stdin)}, ${quoted(expected)});"
        }
        return generated(stdinSource("$holder.$call", cases.joinToString("\n")))
    }

    /**
     * Calls the user's `Main` per example with `Console.SetIn`/`SetOut` swapped to
     * in-memory readers — Java's pattern. The skeleton's `Console.Clear()` targets the
     * real console rather than the swapped writer; its behaviour under redirection is what
     * the execution suite measures per OS. The same two normalisations as every runner,
     * and only those.
     */
    private fun stdinSource(invocation: String, cases: String): String = """
$HEADER
using System;
using System.IO;

public static class RunnerTest
{
    private static int failures = 0;

    public static void Main()
    {
$cases
        Console.WriteLine(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) Environment.Exit(1);
    }

    private static void Check(int index, string stdinText, string expected)
    {
        TextReader originalIn = Console.In;
        TextWriter originalOut = Console.Out;
        var captured = new StringWriter();
        try
        {
            Console.SetIn(new StringReader(stdinText));
            Console.SetOut(captured);
            $invocation;
        }
        finally
        {
            Console.SetOut(originalOut);
            Console.SetIn(originalIn);
        }
        // Two normalisations, and only these two: trailing newlines and CRLF to LF.
        string actual = captured.ToString().Replace("\r\n", "\n").TrimEnd('\n');
        string want = expected.Replace("\r\n", "\n").TrimEnd('\n');
        bool ok = actual == want;
        string detail = "  FAIL — expected <" + want + ">, got <" + actual + ">";
        Console.WriteLine("example " + index + (ok ? "  PASS" : detail));
        if (!ok) failures += 1;
    }
}
    """.trimIndent() + "\n"

    private fun generated(source: String): Runner.Generated =
        Runner.Generated(FILE_NAME, source, listOf(Runner.ExtraFile(PROJECT_FILE, PROJECT)))

    private const val FILE_NAME = "runner_test.cs"

    private const val PROJECT_FILE = "runner_test.csproj"

    private const val HEADER =
        "// Generated by programmers-tracker from the judge's own examples — do not edit; it is\n" +
            "// replaced after every run. Run with (Solution.cs and runner_test.csproj beside it):\n" +
            "//   dotnet run --project runner_test.csproj"

    /**
     * Compiles exactly the two files: default globbing would swallow every `.cs` under
     * `attempts/`, each declaring another `Solution`. `StartupObject` pins the entry to
     * the harness — the measured main-style skeleton has a `Main` of its own.
     */
    private val PROJECT = """
        <Project Sdk="Microsoft.NET.Sdk">
          <PropertyGroup>
            <OutputType>Exe</OutputType>
            <TargetFramework>net8.0</TargetFramework>
            <RollForward>LatestMajor</RollForward>
            <Nullable>disable</Nullable>
            <ImplicitUsings>disable</ImplicitUsings>
            <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
            <AssemblyName>runner_test</AssemblyName>
            <StartupObject>RunnerTest</StartupObject>
          </PropertyGroup>
          <ItemGroup>
            <Compile Include="Solution.cs" />
            <Compile Include="runner_test.cs" />
          </ItemGroup>
        </Project>
    """.trimIndent() + "\n"
}
