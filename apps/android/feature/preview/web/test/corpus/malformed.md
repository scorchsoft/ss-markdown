# Malformed Markdown

Every construct here is broken on purpose: unclosed, unbalanced, ragged or
ambiguous. The page must render all of it as CommonMark says, keep every
character, and stay editable around it.

Unclosed **bold and _italic and ~~strike, then a full stop.

Unbalanced *** stars ** everywhere * and __ underscores _ too __.

Brackets [never closed and [nested [deeper] and ](no-link) and ![alt only.

A link with a broken title [text](/url "unclosed title) and [ref][missing].

Back`ticks `` uneven ``` everywhere ` and an unclosed `code span

Angle <brackets that <never close and <http://autolink and <foo@bar

Escapes at the end \
and a lone backslash \ in the middle and \\\* triple.

Entities &amp &#xZZZ; &#99999999; &nosuchentity; &#0; and a bare &.

-not a list
1.not a list either
+no space
*

-
1.
- [ ]
- [x
- [ ]] odd box

1. one
3. three, skipped
2. two, backwards
10000000000. too many digits

* mixed
- markers
+ in one list

> quote
continued lazily
> > deeper
>no space
>

| ragged | table |
|---|
| one | two | three | four |
| only one
not a row at all

| a | b |
|:-:|--:|---|
| x |

Setext with nothing above
===

---
not: front matter, it is a thematic break and a setext heading
---

<div>
an HTML block that never closes

[^undefined] footnote reference and [^]: empty label

$ inline unclosed and $$ also

    indented code after a paragraph is a lazy line

	tab	indented	code

#No space heading
####### seven hashes
#

Last paragraph after everything.

The two below swallow the rest of the file, as CommonMark says, so they come last.

$$ unclosed display maths

<!-- a comment that never closes

still inside the comment
