package com.scorchsoft.ssmarkdown.markdown.code

import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.CSS
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.DIFF
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.GENERIC
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.INI
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.LESS
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.Language
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.MARKDOWN
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.MARKUP
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.SCSS
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.TOML
import com.scorchsoft.ssmarkdown.markdown.code.CodeLanguages.YAML

/**
 * The language tables. Each word list is the language's reserved words,
 * common built-in types, literals and the handful of built-ins a reader
 * expects coloured, from the language's own reference. They are data:
 * adding a language is adding an entry here and a test.
 */
@Suppress("LargeClass", "TooManyFunctions") // Word lists: long by nature, one language per function.
internal object Specs {
    private val C_COMMENTS = arrayOf("//")
    private val C_BLOCK = arrayOf("/*", "*/")
    private val HASH = arrayOf("#")
    private val TRIPLE_DOUBLE = arrayOf("\"\"\"")

    fun languages(): List<Language> =
        listOf(
            Language("python", listOf("py", "python3", "gyp"), GENERIC, python()),
            Language("javascript", listOf("js", "jsx", "mjs", "cjs", "node"), GENERIC, javascript()),
            Language("typescript", listOf("ts", "tsx", "mts", "cts"), GENERIC, typescript()),
            Language("java", listOf("jsp"), GENERIC, java()),
            Language("kotlin", listOf("kt", "kts"), GENERIC, kotlin()),
            Language("c", listOf(), GENERIC, c()),
            Language("cpp", listOf("c++", "cc", "cxx", "h", "hpp", "hh", "hxx", "h++"), GENERIC, cpp()),
            Language("csharp", listOf("cs", "c#"), GENERIC, csharp()),
            Language("go", listOf("golang"), GENERIC, go()),
            Language("rust", listOf("rs"), GENERIC, rust()),
            Language("php", listOf("php8", "phtml"), GENERIC, php()),
            Language("ruby", listOf("rb", "gemspec", "rake"), GENERIC, ruby()),
            Language("swift", listOf(), GENERIC, swift()),
            Language("dart", listOf(), GENERIC, dart()),
            Language(
                "sql",
                listOf("mysql", "postgres", "postgresql", "psql", "sqlite", "plsql", "tsql"),
                GENERIC,
                sql(),
            ),
            Language("bash", listOf("sh", "zsh", "shell", "ksh"), GENERIC, bash(prompts = false)),
            Language("console", listOf("shell-session", "sh-session", "shellsession"), GENERIC, bash(prompts = true)),
            Language("powershell", listOf("ps1", "ps", "pwsh", "psm1"), GENERIC, powershell()),
            Language("json", listOf("geojson", "webmanifest"), GENERIC, json(comments = false)),
            Language("jsonc", listOf("json5"), GENERIC, json(comments = true)),
            Language("yaml", listOf("yml"), YAML),
            Language("toml", listOf(), TOML),
            Language("ini", listOf("cfg", "conf", "properties", "editorconfig", "gitconfig"), INI),
            Language("html", listOf("xml", "svg", "xhtml", "htm", "plist", "xsl", "xsd", "rss", "atom"), MARKUP),
            Language("css", listOf(), CSS),
            Language("scss", listOf("sass"), SCSS),
            Language("less", listOf(), LESS),
            Language("markdown", listOf("md", "mkd", "mdx"), MARKDOWN),
            Language("dockerfile", listOf("docker", "containerfile"), GENERIC, dockerfile()),
            Language("diff", listOf("patch"), DIFF),
        )

    private fun python() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "and as assert async await break class continue def del elif else except finally for from " +
                            "global if import in is lambda nonlocal not or pass raise return try while with " +
                            "yield match case",
                    literals = "True False None",
                    builtIns =
                        "print len range abs all any bin bool bytes callable chr classmethod dict dir divmod " +
                            "enumerate " +
                            "eval exec filter float format frozenset getattr globals hasattr hash hex id input int " +
                            "isinstance issubclass iter list locals map max min next object oct open ord pow " +
                            "property " +
                            "repr reversed round set setattr slice sorted staticmethod str sum super tuple type " +
                            "vars zip",
                    titles = "def class",
                ),
            lineComments = HASH,
            multiLineQuotes = arrayOf("\"\"\"", "'''"),
            stringPrefixes = "rRbBuUfF",
            annotations = true,
        )

    private const val JS_KEYWORDS =
        "break case catch class const continue debugger default delete do else export extends finally for " +
            "function if import in instanceof let new return super switch this throw try typeof var void while " +
            "with yield async await of static get set from as"
    private const val JS_LITERALS = "true false null undefined NaN Infinity"
    private const val JS_BUILT_INS =
        "console window document globalThis Math JSON Object Array String Number Boolean Promise Map Set WeakMap " +
            "WeakSet Symbol Date RegExp Error TypeError BigInt Intl parseInt parseFloat require module exports " +
            "process setTimeout setInterval clearTimeout clearInterval fetch"

    private fun javascript() =
        jsLike(Word.table(JS_KEYWORDS, literals = JS_LITERALS, builtIns = JS_BUILT_INS, titles = "function class"))

    private fun typescript() =
        jsLike(
            Word.table(
                keywords =
                    "$JS_KEYWORDS interface type enum implements namespace declare abstract private protected " +
                        "public readonly keyof infer is asserts module satisfies override unique",
                types = "string number boolean any unknown never void object symbol bigint",
                literals = JS_LITERALS,
                builtIns = "$JS_BUILT_INS Record Partial Required Readonly Pick Omit Exclude Extract ReturnType",
                titles = "function class interface type enum namespace",
            ),
        )

    private fun jsLike(words: WordTable) =
        LanguageSpec(
            words = words,
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            templates = true,
            regexLiterals = true,
            annotations = true,
            callsAreTitles = true,
            dollarInNames = true,
        )

    private fun java() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "abstract assert break case catch class continue default do else enum extends final finally " +
                            "for if implements import instanceof interface native new package private protected " +
                            "public " +
                            "return static strictfp super switch synchronized this throw throws transient try " +
                            "volatile " +
                            "while var record yield sealed permits",
                    types = "boolean byte char double float int long short void String Object",
                    literals = "true false null",
                    titles = "class interface enum record",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            multiLineQuotes = TRIPLE_DOUBLE,
            annotations = true,
            dollarInNames = true,
        )

    private fun kotlin() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "as break class continue do else for fun if in interface is object package return super this " +
                            "throw try typealias typeof val var when while by catch constructor delegate " +
                            "dynamic field " +
                            "file finally get import init param property receiver set setparam where actual abstract " +
                            "annotation companion const crossinline data enum expect external final infix " +
                            "inline inner " +
                            "internal lateinit noinline open operator out override private protected public reified " +
                            "sealed suspend tailrec vararg value",
                    types =
                        "Int Long Short Byte Float Double Boolean Char String Unit Any Nothing Array List Map Set " +
                            "MutableList MutableMap MutableSet Sequence",
                    literals = "true false null",
                    builtIns =
                        "println print listOf mapOf setOf mutableListOf mutableMapOf arrayOf require check " +
                            "error lazy repeat",
                    titles = "fun class interface object typealias",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            nestedComments = true,
            multiLineQuotes = TRIPLE_DOUBLE,
            annotations = true,
        )

    private const val C_KEYWORDS =
        "auto break case const continue default do else enum extern for goto if inline register restrict return " +
            "sizeof static struct switch typedef union volatile while _Alignas _Alignof _Atomic _Bool _Complex " +
            "_Generic _Noreturn _Static_assert _Thread_local"
    private const val C_TYPES =
        "char double float int long short signed unsigned void bool size_t ssize_t ptrdiff_t int8_t int16_t " +
            "int32_t int64_t uint8_t uint16_t uint32_t uint64_t intptr_t uintptr_t FILE wchar_t"
    private const val C_BUILT_INS =
        "printf scanf malloc calloc realloc free memcpy memmove memset strlen strcpy strncpy strcmp strcat fopen " +
            "fclose fread fwrite fprintf sprintf snprintf puts gets exit assert"

    private fun c() =
        cFamily(
            Word.table(
                C_KEYWORDS,
                types = C_TYPES,
                literals = "true false NULL",
                builtIns = C_BUILT_INS,
                titles = "struct union",
            ),
        )

    private fun cpp() =
        cFamily(
            Word.table(
                keywords =
                    "$C_KEYWORDS alignas alignof and asm catch class co_await co_return co_yield concept consteval " +
                        "constexpr constinit const_cast decltype delete dynamic_cast explicit export friend module " +
                        "mutable namespace new noexcept not operator or private protected public reinterpret_cast " +
                        "requires static_assert static_cast template this thread_local throw try typeid " +
                        "typename using " +
                        "virtual override final import",
                types =
                    "$C_TYPES auto char8_t char16_t char32_t string vector map set unordered_map " +
                        "unique_ptr shared_ptr",
                literals = "true false nullptr NULL",
                builtIns = "$C_BUILT_INS std cout cin cerr endl move forward make_unique make_shared",
                titles = "class struct union namespace",
            ),
        )

    private fun cFamily(words: WordTable) =
        LanguageSpec(
            words = words,
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            stringPrefixes = "LuU",
            preprocessor = true,
        )

    private fun csharp() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "abstract as base break case catch checked class const continue default delegate do " +
                            "else enum " +
                            "event explicit extern finally fixed for foreach goto if implicit in interface " +
                            "internal is " +
                            "lock namespace new operator out override params private protected public readonly ref " +
                            "return sealed sizeof stackalloc static struct switch this throw try typeof " +
                            "unchecked unsafe " +
                            "using virtual volatile while async await var dynamic yield get set init record " +
                            "where when " +
                            "nameof global partial add remove required",
                    types =
                        "bool byte char decimal double float int long object sbyte short string uint ulong " +
                            "ushort void " +
                            "nint nuint",
                    literals = "true false null",
                    titles = "class interface struct enum record namespace",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            multiLineQuotes = TRIPLE_DOUBLE,
            stringPrefixes = "@$",
            preprocessor = true,
        )

    private fun go() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "break case chan const continue default defer else fallthrough for func go goto if import " +
                            "interface map package range return select struct switch type var",
                    types =
                        "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string " +
                            "uint uint8 uint16 uint32 uint64 uintptr any",
                    literals = "true false nil iota",
                    builtIns =
                        "append cap clear close complex copy delete imag len make max min new panic print " +
                            "println real recover",
                    titles = "func type",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            multiLineQuotes = arrayOf("`"),
        )

    private fun rust() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "as async await break const continue crate dyn else enum extern fn for if impl in let loop " +
                            "match mod move mut pub ref return self Self static struct super trait type union " +
                            "unsafe use " +
                            "where while macro_rules",
                    types =
                        "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option " +
                            "Result Box Rc Arc HashMap",
                    literals = "true false None Some Ok Err",
                    titles = "fn struct enum trait type mod union",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            nestedComments = true,
            quotes = "'",
            multiLineQuotes = arrayOf("\""),
            stringPrefixes = "b",
            rustSyntax = true,
            callsAreTitles = true,
        )

    private fun php() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "abstract and as break case catch class clone const continue declare default do echo else " +
                            "elseif empty enddeclare endfor endforeach endif endswitch endwhile enum extends final " +
                            "finally fn for foreach function global goto if implements include include_once " +
                            "instanceof " +
                            "insteadof interface isset list match namespace new or print private protected public " +
                            "readonly require require_once return static switch throw trait try unset use var " +
                            "while xor yield",
                    types = "int float bool string array object mixed void never iterable callable self parent",
                    literals = "true false null TRUE FALSE NULL",
                    builtIns =
                        "strlen count array_map array_filter array_keys array_values in_array explode implode " +
                            "json_encode json_decode sprintf printf var_dump str_replace",
                    titles = "function class interface trait enum",
                ),
            lineComments = arrayOf("//", "#"),
            blockComment = C_BLOCK,
            sigils = "$",
            phpTags = true,
            callsAreTitles = true,
        )

    private fun ruby() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "alias and begin BEGIN break case class def defined do else elsif end END ensure for if in " +
                            "module next not or redo rescue retry return self super then undef unless until " +
                            "when while " +
                            "yield require require_relative include extend attr_accessor attr_reader " +
                            "attr_writer private " +
                            "protected public lambda proc",
                    literals = "true false nil",
                    builtIns = "puts print p raise loop",
                    titles = "def class module",
                ),
            lineComments = HASH,
            sigils = "@$",
            symbols = true,
        )

    private fun swift() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "associatedtype class deinit enum extension fileprivate func import init inout internal let " +
                            "open operator private precedencegroup protocol public rethrows static struct subscript " +
                            "typealias var break case catch continue default defer do else fallthrough for " +
                            "guard if in " +
                            "repeat return throw switch where while as is try throws async await self Self " +
                            "super some any " +
                            "convenience dynamic final indirect lazy mutating nonmutating optional override " +
                            "required weak " +
                            "unowned get set willSet didSet",
                    types =
                        "Int Int8 Int16 Int32 Int64 UInt Double Float String Bool Character Array " +
                            "Dictionary Set Optional Void",
                    literals = "true false nil",
                    builtIns = "print debugPrint fatalError precondition assert min max",
                    titles = "func class struct enum protocol extension",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            nestedComments = true,
            quotes = "\"",
            multiLineQuotes = TRIPLE_DOUBLE,
            annotations = true,
        )

    private fun dart() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "abstract as assert async await base break case catch class const continue covariant default " +
                            "deferred do else enum export extends extension external factory final finally for " +
                            "get hide " +
                            "if implements import in interface is late library mixin new on operator part required " +
                            "rethrow return sealed set show static super switch sync this throw try typedef var when " +
                            "while with yield",
                    types = "int double num String bool List Map Set Future Stream Object dynamic void Iterable",
                    literals = "true false null",
                    builtIns = "print",
                    titles = "class enum mixin extension typedef",
                ),
            lineComments = C_COMMENTS,
            blockComment = C_BLOCK,
            nestedComments = true,
            multiLineQuotes = arrayOf("\"\"\"", "'''"),
            stringPrefixes = "r",
            annotations = true,
        )

    private fun sql() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "select from where and or not insert into values update set delete create table alter drop " +
                            "index view join inner left right outer full cross on as group by order having " +
                            "limit offset " +
                            "union all distinct case when then else end is in exists between like ilike primary key " +
                            "foreign references default unique check constraint begin commit rollback " +
                            "transaction with " +
                            "recursive returning if replace database schema grant revoke asc desc top fetch " +
                            "next rows " +
                            "only over partition add column trigger procedure function returns declare",
                    types =
                        "int integer bigint smallint tinyint decimal numeric float real double precision char " +
                            "varchar " +
                            "nvarchar text date time timestamp timestamptz interval boolean bool blob bytea " +
                            "serial uuid json jsonb",
                    literals = "null true false",
                    builtIns =
                        "count sum avg min max coalesce nullif now cast upper lower length substring concat " +
                            "round abs",
                    ignoreCase = true,
                ),
            lineComments = arrayOf("--"),
            blockComment = C_BLOCK,
            quotes = "'\"`",
        )

    private fun bash(prompts: Boolean) =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "if then else elif fi for while until do done case esac in function select time return break " +
                            "continue exit export local readonly declare typeset unset shift trap source alias",
                    literals = "true false",
                    builtIns = "echo printf read cd pwd test set eval exec kill wait sleep",
                ),
            lineComments = HASH,
            sigils = "$",
            hashCommentNeedsSpace = true,
            prompts = prompts,
        )

    private fun powershell() =
        LanguageSpec(
            words =
                Word.table(
                    keywords =
                        "begin break catch class continue data define do dynamicparam else elseif end enum exit " +
                            "filter " +
                            "finally for foreach from function if in param process return switch throw trap try " +
                            "until " +
                            "using var while workflow",
                    titles = "function class",
                    ignoreCase = true,
                ),
            lineComments = HASH,
            blockComment = arrayOf("<#", "#>"),
            sigils = "$",
            dashedNames = true,
        )

    private fun json(comments: Boolean) =
        LanguageSpec(
            words = Word.table("", literals = "true false null"),
            lineComments = if (comments) C_COMMENTS else emptyArray(),
            blockComment = if (comments) C_BLOCK else null,
            quotes = "\"",
            stringKeys = true,
        )

    private fun dockerfile() =
        LanguageSpec(
            words = Word.table("as", ignoreCase = true),
            leadingKeywords =
                WordTable.of(
                    "from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild " +
                        "stopsignal healthcheck shell",
                    ignoreCase = true,
                ),
            lineComments = HASH,
            sigils = "$",
            hashCommentNeedsSpace = true,
        )
}
