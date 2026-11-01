package com.scorchsoft.ssmarkdown.markdown.edit

/** The list types the strip can toggle. */
public enum class ListKind {
    /** `- item`, with the document's own bullet char. */
    Bullet,

    /** `1. item`, with the document's own delimiter. */
    Numbered,

    /** `- [ ] item`, a GFM task. */
    Task,
}
