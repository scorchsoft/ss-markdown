# Markdown + HTML render test

This file mixes **Markdown**, *inline HTML*, and HTML blocks. Different renderers may display, escape, or remove HTML and styling; the examples below make those differences visible.

## 1. Ordinary Markdown

A paragraph with **bold**, *italic*, ~~strikethrough~~, and `inline code`.

- First bullet
- Second bullet with **bold text**
  - Nested bullet

1. First numbered item
2. Second numbered item

> A Markdown quotation with **bold text**.

| Feature | Expected appearance |
| --- | --- |
| Bold | **Strong emphasis** |
| Code | `example()` |

- [x] Completed task
- [ ] Incomplete task

---

## 2. Inline HTML inside Markdown

This sentence contains <strong>HTML bold</strong>, <em>HTML italics</em>, <mark>highlighted text</mark>, and <small>small print</small>.

Water: H<sub>2</sub>O. Square metres: m<sup>2</sup>.

Press <kbd>Ctrl</kbd> + <kbd>S</kbd> to save. This is an <abbr title="HyperText Markup Language">HTML</abbr> abbreviation.

This has a manual break:<br>
The next line should appear directly below it.

Markdown **bold around <em>HTML italic text</em>** and HTML <strong>bold around *Markdown italic text*</strong>.

## 3. A plain HTML block

<div>
  <h3>HTML heading inside a div</h3>
  <p>This paragraph uses <strong>HTML bold</strong> and <em>HTML italics</em>.</p>
  <ul>
    <li>HTML list item one</li>
    <li>HTML list item two</li>
  </ul>
</div>

This is ordinary **Markdown after the HTML block**.

## 4. HTML with inline styles

<div style="padding: 16px; border: 2px solid #008080; border-radius: 12px; background: #e6f7f7; color: #123333;">
  <h3 style="margin-top: 0;">Styled HTML card</h3>
  <p>If inline styles are supported, this card has a teal border, a pale teal background, and rounded corners.</p>
  <p><span style="color: #c62828; font-weight: bold;">This sentence should be red and bold.</span></p>
</div>

Back to a normal Markdown paragraph.

## 5. Markdown inside HTML: blank-line comparison

The following variants test how the renderer handles Markdown nested in HTML containers.

### A. No blank lines inside the container

<div>
**Bold Markdown inside a div**
- Markdown bullet inside a div
</div>

### B. Blank lines inside the container

<div>

**Bold Markdown inside a div**

- First Markdown bullet
- Second Markdown bullet

</div>

### C. The markdown attribute (an extension in some renderers)

<div markdown="1">

**Bold Markdown inside a div with markdown="1"**

- First Markdown bullet
- Second Markdown bullet

</div>

Normal Markdown resumes here: **this should be bold**.

## 6. Expandable HTML details

<details>
<summary>Click to expand: HTML content</summary>
<p>This is a hidden paragraph with <strong>HTML bold</strong>.</p>
</details>

<details>
<summary>Click to expand: Markdown content with blank lines</summary>

### Heading inside details

This paragraph contains **Markdown bold** and `inline code`.

- An item inside the expandable section
- Another item

</details>

## 7. HTML table alongside a Markdown table

<table>
  <thead>
    <tr><th>Type</th><th>Example</th></tr>
  </thead>
  <tbody>
    <tr><td>HTML formatting</td><td><strong>Bold</strong> and <em>italic</em></td></tr>
    <tr><td>Line break</td><td>First line<br>Second line</td></tr>
    <tr><td colspan="2">This cell spans two columns.</td></tr>
  </tbody>
</table>

| Type | Example |
| --- | --- |
| Markdown formatting | **Bold** and *italic* |
| Inline HTML | First line<br>Second line |

## 8. Links and an embedded image

[A Markdown link to example.com](https://example.com)

<a href="https://example.com" title="HTML link tooltip">An HTML link to example.com</a>

The following image uses an embedded SVG data URL, so no external image download is needed. Some renderers block this format; the alt text should then identify the example.

<img src="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='240' height='80'%3E%3Crect width='240' height='80' rx='12' fill='%23008080'/%3E%3Ctext x='120' y='47' text-anchor='middle' font-family='sans-serif' font-size='20' fill='white'%3EHTML image test%3C/text%3E%3C/svg%3E" alt="Embedded teal image labelled HTML image test" width="240" height="80">

## 9. HTML entities and literal tags

Entities: &amp; &lt; &gt; &copy; &mdash; &pound;.

Escaped HTML should appear as text: &lt;strong&gt;not bold&lt;/strong&gt;.

Inline code should show the tags literally: `<strong>not rendered</strong>`.

## 10. Fenced HTML versus raw HTML

This fenced example should display as source code:

```html
<div style="background: gold; padding: 12px;">
  <strong>This is source code, not a rendered card.</strong>
</div>
```

This is the same kind of HTML outside a code fence:

<div style="background: gold; padding: 12px;">
  <strong>This is a raw HTML card.</strong>
</div>

## 11. Preformatted HTML code

<pre><code>function greet(name) {
  return "Hello, " + name;
}
</code></pre>

## 12. HTML comment and horizontal rule

The next line contains a comment that should not appear in the rendered output.

<!-- COMMENT TEST: visible in source, hidden in rendered output. -->

<hr>

**End of test.** This final Markdown paragraph checks that normal parsing resumes after the HTML examples.
