package com.scorchsoft.ssmarkdown.markdown.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One sample per language covering each token class it has, plus the
 * tricky cases from docs/edge-cases.md ("Code highlighting").
 */
class ProgrammingLanguagesTest {
    @Test
    fun `python covers every class`() {
        assertLines(
            "python",
            """
            @app.route("/x")
            def greet(name: str) -> None:
                # say hi
                print(f"hi {name}", 0x1F, 3.5e-2, True)
            class Box: pass
            """.trimIndent(),
            listOf("META:@app.route", "STRING:\"/x\""),
            listOf("KEYWORD:def", "FUNCTION:greet", "BUILT_IN:str", "LITERAL:None"),
            listOf("COMMENT:# say hi"),
            listOf("BUILT_IN:print", "STRING:f\"hi {name}\"", "NUMBER:0x1F", "NUMBER:3.5e-2", "LITERAL:True"),
            listOf("KEYWORD:class", "FUNCTION:Box", "KEYWORD:pass"),
        )
    }

    @Test
    fun `python triple quotes span lines and hide comment markers`() {
        val lines =
            highlight(
                "py",
                "x = '''start # not a comment\n  middle \"quoted\"\nend''' # real\ny = 1",
            )
        assertEquals(listOf("STRING:'''start # not a comment"), lines[0].runs)
        assertEquals(listOf("STRING:  middle \"quoted\""), lines[1].runs)
        assertEquals(listOf("STRING:end'''", "COMMENT:# real"), lines[2].runs)
        assertEquals(listOf("NUMBER:1"), lines[3].runs)
    }

    @Test
    fun `strings hold comment markers and escaped quotes`() {
        assertLines(
            "js",
            """
            const a = "// not \" a comment"; // real
            const b = '/* nor this */';
            """.trimIndent(),
            listOf("KEYWORD:const", "STRING:\"// not \\\" a comment\"", "COMMENT:// real"),
            listOf("KEYWORD:const", "STRING:'/* nor this */'"),
        )
    }

    @Test
    fun `javascript template literals with interpolation span lines`() {
        val lines = highlight("javascript", "const s = `a \${x + 1} b\n  \${fn(`in`)} c` + 2;\nlet y = 3")
        assertEquals(listOf("KEYWORD:const", "STRING:`a ", "NUMBER:1", "STRING: b"), lines[0].runs)
        assertEquals(listOf("STRING:  ", "FUNCTION:fn", "STRING:`in`", "STRING: c`", "NUMBER:2"), lines[1].runs)
        assertEquals(listOf("KEYWORD:let", "NUMBER:3"), lines[2].runs)
    }

    @Test
    fun `a template expression can span lines`() {
        val lines = highlight("js", "`a \${ {\n  k: 1 }\n} b` // c")
        assertEquals(listOf("STRING:`a "), lines[0].runs)
        assertEquals(listOf("NUMBER:1"), lines[1].runs)
        assertEquals(listOf("STRING: b`", "COMMENT:// c"), lines[2].runs)
    }

    @Test
    fun `regex literals after operators, division after values`() {
        assertRuns("js", "const r = /a\\/[/]b/gi; const d = x / 2 / y;", "REGEXP:/a\\/[/]b/gi", "NUMBER:2")
        assertTrue(runs("js", "const d = x / 2 / y;").none { it.startsWith("REGEXP") })
        assertTrue(runs("js", "if (a) return /x/.test(s)").contains("REGEXP:/x/"))
        assertTrue("an unclosed / is division", runs("js", "a = b / c").none { it.startsWith("REGEXP") })
    }

    @Test
    fun `typescript types, generics and decorators`() {
        assertLines(
            "ts",
            """
            @Component({ selector: "x" })
            export interface Box<T extends Map<string, Array<number>>> { v: T }
            function id<T>(x: T): T { return x >> 1 }
            """.trimIndent(),
            listOf("META:@Component", "STRING:\"x\""),
            listOf(
                "KEYWORD:export",
                "KEYWORD:interface",
                "FUNCTION:Box",
                "KEYWORD:extends",
                "BUILT_IN:Map",
                "TYPE:string",
                "BUILT_IN:Array",
                "TYPE:number",
            ),
            listOf("KEYWORD:function", "FUNCTION:id", "KEYWORD:return", "NUMBER:1"),
        )
    }

    @Test
    fun `java generics, annotations and text blocks`() {
        val lines =
            highlight(
                "java",
                """
                @Override
                public Map<String, List<Integer>> get(int n) { return null; }
                String s = ""${'"'}
                  text "block" // here
                  ""${'"'}; char c = '\'';
                """.trimIndent(),
            )
        assertEquals(listOf("META:@Override"), lines[0].runs)
        assertEquals(
            listOf("KEYWORD:public", "TYPE:String", "TYPE:int", "KEYWORD:return", "LITERAL:null"),
            lines[1].runs,
        )
        assertEquals(listOf("TYPE:String", "STRING:\"\"\""), lines[2].runs)
        assertEquals(listOf("STRING:  text \"block\" // here"), lines[3].runs)
        assertEquals(listOf("STRING:  \"\"\"", "TYPE:char", "STRING:'\\''"), lines[4].runs)
    }

    @Test
    fun `c block comments span lines and the preprocessor is meta`() {
        val lines =
            highlight(
                "c",
                """
                #include <stdio.h>
                /* a "comment"
                   spanning */ int x = 0; // done
                int main(void) { printf("%d\n", x); return 0; }
                """.trimIndent(),
            )
        assertEquals(listOf("META:#include", "STRING:<stdio.h>"), lines[0].runs)
        assertEquals(listOf("COMMENT:/* a \"comment\""), lines[1].runs)
        assertEquals(listOf("COMMENT:   spanning */", "TYPE:int", "NUMBER:0", "COMMENT:// done"), lines[2].runs)
        assertTrue(lines[3].runs.containsAll(listOf("TYPE:int", "TYPE:void", "BUILT_IN:printf", "KEYWORD:return")))
    }

    @Test
    fun `c++ and c# words`() {
        assertRuns(
            "cpp",
            "template <typename T> class Box { std::vector<T> v; nullptr; };",
            "KEYWORD:template",
            "KEYWORD:class",
            "FUNCTION:Box",
            "TYPE:vector",
            "LITERAL:nullptr",
        )
        assertRuns(
            "cs",
            "public record Point(int X); var p = @\"C:\\dir\\\"; string s = \$\"{p}\";",
            "KEYWORD:record",
            "FUNCTION:Point",
            "STRING:@\"C:\\dir\\\"",
            "STRING:\$\"{p}\"",
        )
    }

    @Test
    fun `php tags, variables and comments`() {
        assertLines(
            "php",
            """
            <?php
            function greet(${'$'}name) { echo "Hi " . ${'$'}name; } # done
            #[Attribute] ${'$'}x = strlen('a'); ?>
            """.trimIndent(),
            listOf("META:<?php"),
            listOf(
                "KEYWORD:function",
                "FUNCTION:greet",
                "VARIABLE:\$name",
                "KEYWORD:echo",
                "STRING:\"Hi \"",
                "VARIABLE:\$name",
                "COMMENT:# done",
            ),
            listOf("META:#[Attribute]", "VARIABLE:\$x", "BUILT_IN:strlen", "STRING:'a'", "META:?>"),
        )
    }

    @Test
    fun `go raw strings span lines`() {
        val lines = highlight("golang", "func main() {\n\ts := `raw \\ \"x\"\nmore` + \"y\"\n}")
        assertEquals(listOf("KEYWORD:func", "FUNCTION:main"), lines[0].runs)
        assertEquals(listOf("STRING:`raw \\ \"x\""), lines[1].runs)
        assertEquals(listOf("STRING:more`", "STRING:\"y\""), lines[2].runs)
    }

    @Test
    fun `rust lifetimes, chars, raw strings, attributes and nested comments`() {
        val lines =
            highlight(
                "rs",
                """
                #[derive(Debug)]
                fn f<'a>(s: &'a str) -> char { println!("{}", s); 'x' }
                let r = r#"raw "quoted"
                still"#; /* outer /* inner */ still */ let n = 1;
                """.trimIndent(),
            )
        assertEquals(listOf("META:#[derive(Debug)]"), lines[0].runs)
        assertEquals(
            listOf(
                "KEYWORD:fn",
                "FUNCTION:f",
                "SYMBOL:'a",
                "SYMBOL:'a",
                "TYPE:str",
                "TYPE:char",
                "BUILT_IN:println!",
                "STRING:\"{}\"",
                "STRING:'x'",
            ),
            lines[1].runs,
        )
        assertEquals(listOf("KEYWORD:let", "STRING:r#\"raw \"quoted\""), lines[2].runs)
        assertEquals(
            listOf("STRING:still\"#", "COMMENT:/* outer /* inner */ still */", "KEYWORD:let", "NUMBER:1"),
            lines[3].runs,
        )
    }

    @Test
    fun `kotlin, swift and dart`() {
        assertRuns(
            "kt",
            "@JvmStatic fun main() { val s: String = \"\"; println(s) }",
            "META:@JvmStatic",
            "KEYWORD:fun",
            "FUNCTION:main",
            "TYPE:String",
            "BUILT_IN:println",
        )
        assertRuns(
            "swift",
            "@objc func go() -> Int { guard let x = y else { return nil } }",
            "META:@objc",
            "KEYWORD:func",
            "FUNCTION:go",
            "TYPE:Int",
            "KEYWORD:guard",
            "LITERAL:nil",
        )
        assertRuns(
            "dart",
            "Future<void> main() async { final s = r'raw\\n'; }",
            "TYPE:Future",
            "TYPE:void",
            "KEYWORD:async",
            "STRING:r'raw\\n'",
        )
    }

    @Test
    fun `ruby symbols and instance variables`() {
        assertRuns(
            "rb",
            "def initialize(name)\n  @name = name; puts :done # c\nend",
            "KEYWORD:def",
            "FUNCTION:initialize",
            "VARIABLE:@name",
            "BUILT_IN:puts",
            "SYMBOL::done",
            "COMMENT:# c",
            "KEYWORD:end",
        )
    }

    @Test
    fun `sql is case-insensitive`() {
        assertRuns(
            "sql",
            "SELECT name, COUNT(*) FROM users WHERE id = 'it''s' -- c",
            "KEYWORD:SELECT",
            "BUILT_IN:COUNT",
            "KEYWORD:FROM",
            "STRING:'it''s'",
            "COMMENT:-- c",
        )
        assertRuns(
            "postgres",
            "create table t (id serial primary key, data jsonb null);",
            "KEYWORD:create",
            "TYPE:serial",
            "TYPE:jsonb",
            "LITERAL:null",
        )
    }

    @Test
    fun `shell variables, comments and strings`() {
        assertRuns(
            "bash",
            "if [ -n \"\$HOME\" ]; then echo \${PATH} \$1 # c\nfi",
            "KEYWORD:if",
            "STRING:\"\$HOME\"",
            "KEYWORD:then",
            "BUILT_IN:echo",
            "VARIABLE:\${PATH}",
            "VARIABLE:\$1",
            "COMMENT:# c",
            "KEYWORD:fi",
        )
        assertTrue("a # inside a word is not a comment", runs("sh", "echo a#b").none { it.startsWith("COMMENT") })
    }

    @Test
    fun `console colours commands after a prompt and leaves output plain`() {
        val lines = highlight("console", "\$ echo hi # c\nhi\n# whoami")
        assertEquals(listOf("META:\$", "BUILT_IN:echo", "COMMENT:# c"), lines[0].runs)
        assertEquals(emptyList<String>(), lines[1].runs)
        assertEquals(listOf("META:#"), lines[2].runs)
    }

    @Test
    fun `powershell cmdlets, variables and block comments`() {
        val lines = highlight("ps1", "<# a\nb #> Get-ChildItem -Path \$env | ForEach-Object { \$_ }")
        assertEquals(listOf("COMMENT:<# a"), lines[0].runs)
        assertEquals(
            listOf(
                "COMMENT:b #>",
                "BUILT_IN:Get-ChildItem",
                "VARIABLE:\$env",
                "BUILT_IN:ForEach-Object",
                "VARIABLE:\$_",
            ),
            lines[1].runs,
        )
    }

    @Test
    fun `json keys are attributes and jsonc has comments`() {
        assertRuns(
            "json",
            "{\"name\": \"x\", \"n\": -1.5e3, \"ok\": true, \"none\": null}",
            "ATTRIBUTE:\"name\"",
            "STRING:\"x\"",
            "NUMBER:1.5e3",
            "LITERAL:true",
            "LITERAL:null",
        )
        assertRuns("jsonc", "{ // c\n \"a\": 1 /* d */ }", "COMMENT:// c", "ATTRIBUTE:\"a\"", "COMMENT:/* d */")
        assertTrue(runs("json", "// not a comment in JSON").none { it.startsWith("COMMENT") })
    }

    @Test
    fun `dockerfile instructions only as the first word`() {
        assertRuns(
            "Dockerfile",
            "FROM node:20 AS build\nRUN echo from \$HOME # c",
            "KEYWORD:FROM",
            "KEYWORD:AS",
            "KEYWORD:RUN",
            "VARIABLE:\$HOME",
            "COMMENT:# c",
        )
        assertTrue(runs("dockerfile", "RUN echo from").none { it == "KEYWORD:from" })
    }

    @Test
    fun `long lines are lexed only up to the cap`() {
        val line =
            "x = 1 " +
                "a".repeat(
                    com.scorchsoft.ssmarkdown.markdown.LineTokenizer.MAX_LEXED_CODE_COLUMNS,
                ) + " # past the cap"
        val lexed = runs("python", line)
        assertEquals(listOf("NUMBER:1"), lexed)
    }

    @Test
    fun `emoji and right-to-left text inside code are untouched`() {
        assertLines(
            "py",
            "s = \"héllo 😀 שלום\"  # 😀 تعليق",
            listOf("STRING:\"héllo 😀 שלום\"", "COMMENT:# 😀 تعليق"),
        )
    }
}
