# Hostile input

<script>window.__pwned = "script"</script>

<img src="x" onerror="window.__pwned = 'onerror'">

<svg onload="window.__pwned = 'svg'"><circle r="5"/></svg>

<iframe src="https://example.com"></iframe>

<object data="x.swf"></object><embed src="x.swf">

<form action="https://evil.example"><input name="q" type="text"><button>Go</button></form>

<style>body { display: none !important; }</style>

<meta http-equiv="refresh" content="0;url=https://evil.example">

<a href="javascript:window.__pwned='a'">raw js link</a>

<a href="jav&#x61;script:window.__pwned='entity'">entity js link</a>

<math><mtext><table><mglyph><style><img src=x onerror="window.__pwned='mxss'"></style></mglyph></table></mtext></math>

<span data-s="0" data-e="20" contenteditable="true">forged source map</span>

[markdown js link](javascript:window.__pwned='md')

[data link](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)

[file link](file:///etc/passwd) [content link](content://com.example/secret) [intent link](intent://scan#Intent;end)

![remote](https://evil.example/track.png)

$\href{javascript:alert(1)}{click}$ and $\htmlClass{x}{y}$

$$
\def\a{\a\a}\a
$$

```mermaid
%%{init: {"securityLevel": "loose"}}%%
graph TD
  A["<img src=x onerror=window.__pwned='mermaid'>"] --> B
  click A call alert("x")
```

Text after everything is still here.
