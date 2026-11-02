# ADR 0018: A file opened on its own gets its pictures through a folder grant it is checked against

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** Scorchsoft

## Context

`![](img/diagram.png)` resolves against the document's folder. Through
the Storage Access Framework a single file opened from Files, Drive, a
mail attachment or another app (`ACTION_VIEW`, or Open file) arrives with
a grant for that one document and nothing beside it. Until now only
folder mode (a tree grant) and drafts could show relative pictures; every
other way of opening a file showed broken images with no explanation.

The app is offline (`INTERNET` is for fonts only), takes no URI from an
intent extra (SEC-2), reads nothing unbounded (SEC-8) and never lets a
path climb out of what the user granted (SEC-9).

## Options

1. **Ask for the folder every time** a document with relative pictures
   opens. Honest, but a nag on every reopen.
2. **Ask once per document and remember the folder** (`doc URI → tree
   URI`), reusing any folder grant the app already holds that contains
   the document.
3. **Read neighbours through the document's own URI** (path arithmetic on
   the document id). Only on-device storage has path-like ids, and the
   single-document grant does not cover siblings anyway: it would fail
   with a `SecurityException` on most providers and hide why.

## Decision

Option 2.

- **Which tree.** For a document's pictures the app uses, in order: the
  open folder (folder mode), the folder the user granted for this
  document, then any other persisted tree grant. A tree is used only if
  it **contains** the document: the provider places the document inside
  it (`DocumentsContract.findDocumentPath`), or, for a provider that
  cannot, the document id sits under the tree id by whole path segments
  on the same provider. Ids from another provider never match.
- **Asking.** The page's placeholder for such a picture says "Allow
  access to this folder to show its images" with an **Allow access**
  button; ⋮ offers "Show pictures beside this file…" while any picture
  needs it. Both open `ACTION_OPEN_DOCUMENT_TREE` with
  `EXTRA_INITIAL_URI` at the document's parent folder when its id is a
  path (`primary:Notes/a.md` → `primary:Notes`), otherwise the document
  URI, which most pickers open in its folder.
- **Checking.** The picked folder must contain the document. If it does
  not, the user is told ("That folder doesn't hold this file. Choose the
  folder it's in.") and its grant is released, unless folder mode uses it.
- **Remembering.** The association is kept in an app-private preferences
  file (`picture_folders`), at most 256 documents, oldest first out. The
  grant itself is the persisted URI permission (read and write; read only
  if the provider refuses write), so reopening the file shows its
  pictures without asking. A grant the user revokes simply stops
  resolving: the placeholder asks again.
- **Cost.** Lookups go through a short-lived cache of folder listings
  (`TreeListings`: name → id, type, size; 5 s; misses cached too;
  dropped on a grant, a copy into the tree and a folder refresh). A page
  of fifty pictures in one folder costs one `findDocumentPath` and one
  listing per folder walked, not fifty walks.
- **Insert image** reuses the same tree: a picture inside it is linked by
  its relative path; one outside it is copied into `images/` beside the
  document (write grant, asked for with the same flow), never linked by a
  `content://` URI. Drafts copy into the drafts folder's own `images/`.

## Consequences

- One extra prompt, once per document (or once per folder, when several
  files share one), instead of silently broken pictures.
- Providers without tree support (some cloud apps) still cannot show
  relative pictures; the placeholder says why and the user can move the
  file somewhere that can.
- Save as of a draft does not move its pictures: they stay in the drafts
  folder and the user is told so (`PicturesStayedWithDraft`). Copying them
  next to the new file would need its folder's grant at the moment of
  saving; that is a possible follow-up, not done silently.
- The association file holds document and tree URIs, app-private and
  never backed up beyond the app's own data rules.
