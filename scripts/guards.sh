#!/usr/bin/env bash
# Constitution guards — rules CLAUDE.md states that nothing else enforces.
# Runnable locally; CI calls the same script so the two can never drift.
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
report() { printf '\n\033[31mFAIL\033[0m  %s\n' "$1"; fail=1; }
pass() { printf '\033[32mok\033[0m    %s\n' "$1"; }

# ---------------------------------------------------------------------------
# 1. Integration tests must never run in CI.
#    They hit the real Programmers account, so the default `test` task has to
#    exclude the tag. Verified statically because the guard must hold even when
#    no integration test exists yet.
# ---------------------------------------------------------------------------
if grep -q 'excludeTags("integration")' build.gradle.kts; then
  pass "default test task excludes @Tag(\"integration\")"
else
  report "build.gradle.kts no longer excludes @Tag(\"integration\") from the default test task"
fi

if grep -rn "integrationTest" .github/workflows >/dev/null 2>&1; then
  report "a workflow invokes integrationTest — integration tests must never run in CI"
else
  pass "no workflow invokes integrationTest"
fi

# ---------------------------------------------------------------------------
# 2. No credentials or records in the repository.
#    Records belong in ps-records; session cookies belong in memory or an
#    ignored file. Both are absolute prohibitions in CLAUDE.md.
# ---------------------------------------------------------------------------
tracked_ps=$(git ls-files '.ps' | grep -v '^\.ps/\.gitkeep$' || true)
if [ -n "$tracked_ps" ]; then
  report "credential-scoped files are tracked:"$'\n'"$tracked_ps"
else
  pass "no tracked files under .ps/ except .gitkeep"
fi

records=$(git ls-files | grep -E '(^|/)(submissions\.jsonl|attempts/[0-9]{3}\.)' || true)
if [ -n "$records" ]; then
  report "solving records are committed to this repository:"$'\n'"$records"
else
  pass "no solving records committed"
fi

# A real session cookie is a long opaque hex/base64 run with no word separators.
# Synthetic placeholders (`fake-value-for-tests`) carry hyphens or underscores and
# are excluded by the character class, so tests stay writable while a genuine
# credential — or a fixture that was never scrubbed (development-rules §7.3) — is caught.
secrets=$(git grep -nIE '_session_production=[A-Za-z0-9%+/=]{24,}' -- \
  ':!scripts/guards.sh' ':!docs/**' || true)
if [ -n "$secrets" ]; then
  report "something shaped like a live session cookie is committed:"$'\n'"$secrets"
else
  pass "no session-cookie-shaped strings committed"
fi

# ---------------------------------------------------------------------------
# 3. English-only artifacts (development-rules §12).
#    Deliberately narrow: Korean STRING LITERALS are legitimate measured
#    protocol data (`실패 (시간 초과)`), and fixtures, the protocol doc and the
#    design doc all contain them on purpose. What must never be Korean is prose
#    we wrote — and the automatable, false-positive-free slice of that is
#    source comments.
#
#    MATCHED AS BYTES, NOT AS CHARACTERS. `[가-힣]` is a multi-byte range that
#    git grep resolves against the locale, and it agreed with nobody (#123): on
#    the CI runner's C.UTF-8 it died with `Invalid collation character`, in a
#    bare C locale it matched 1006 English comments, and only the author's macOS
#    en_US.UTF-8 gave the two real hits. Hangul syllables U+AC00–U+D7A3 are three
#    UTF-8 bytes led by \xea-\xed; `§` (\xc2) and `—`/`→`/`⚠` (\xe2) fall outside
#    that, so English comments using them stay clean. LC_ALL=C is pinned so the
#    bytes stay bytes on every machine.
#
#    Untracked files are searched too. The gates were run before `git add` and
#    the index held no such file, so the check passed on a tree whose new `.kt`
#    it had never read.
# ---------------------------------------------------------------------------
hangul=$'[\xea-\xed][\x80-\xbf][\x80-\xbf]'
comment_hangul="^[[:space:]]*(//|\*|/\*).*$hangul"

# What failed here was not a missed file but a search that never ran, and a search
# that never ran is indistinguishable from a clean tree. So the machinery is proved
# on two known comments before its silence is trusted: one that must match, one that
# must not. Either canary firing means the check itself is broken, which is a louder
# failure than anything it could find.
korean_checked=1
if ! printf '// %s\n' "$(printf '\xea\xb0\x80')" | LC_ALL=C grep -qE "$comment_hangul"; then
  report "the English-only check cannot detect Korean at all — it would pass any tree"
  korean_checked=0
elif printf '%s\n' '// English prose with § and — and → in it' | LC_ALL=C grep -qE "$comment_hangul"; then
  report "the English-only check matches English prose using § — → — it would fail every tree"
  korean_checked=0
fi

if [ "$korean_checked" -eq 1 ]; then
  # No `|| true`: git grep exits 0 with matches, 1 with none, and higher on error.
  # Collapsing all three is what turned a crash into a pass.
  korean_comments=$(LC_ALL=C git grep -nIE --untracked "$comment_hangul" -- '*.kt' '*.kts' '*.js')
  korean_status=$?
  if [ "$korean_status" -gt 1 ]; then
    report "the English-only check could not run — git grep exited $korean_status"
  elif [ -n "$korean_comments" ]; then
    report "source comments must be written in English (development-rules §12):"$'\n'"$korean_comments"
  else
    pass "no Korean prose in Kotlin or JavaScript comments"
  fi
fi

# ---------------------------------------------------------------------------
# 4. Every repository path a maintained document names must exist.
#    A document asserting a property the code does not have is this project's
#    stated worst outcome, and the mechanically checkable slice of it is the
#    paths: `.claude/commands/` sat in the README's structure block after the
#    directory became `.claude/skills/`, and three documents cited a `LICENSE`
#    that did not exist (#47, #48).
#
#    Narrow on three axes, in the spirit of 2026-08-05-ci-guard-scoping:
#
#    * WHICH DOCUMENTS. Only those the project maintains as current. Dated
#      records are excluded on purpose — `docs/llm-wiki/raw/` is declared
#      immutable by the wiki schema, and wiki pages, specs and plans record a
#      state at a date. A path renamed afterwards would make those documents
#      fire forever, and the only ways out are editing a record we promised not
#      to edit or switching the guard off. Both are worse than not checking them.
#    * WHICH LINES. Markdown links, and structure blocks anchored to a directory
#      this repository actually has. Prose is never parsed.
#    * WHICH TREES. A fenced block is walked only when its first line names a
#      real repository directory (or `.`). `ps-records/`, `problems/<id>-<title>/`
#      and a Java package root all fail that test, so their subtrees — which are
#      relative to something outside this repository — are left alone. Measured
#      before adoption: over all 67 tracked Markdown files these rules judge 34
#      paths and produce zero false positives, where an unanchored scan produced
#      121 (#47).
#
#    Existence is decided against git's index rather than the working tree, so a
#    dirty workspace cannot make the guard pass — the failure mode recorded in
#    concepts/assumption-vs-measurement. `guard:planned` on a line opts it out.
# ---------------------------------------------------------------------------
maintained_docs=$( { git ls-files 'README.md' 'CONTRIBUTING.md' 'CLAUDE.md'
                     git ls-files docs | grep -E '^docs/[^/]+\.md$'; } | sort -u)

export TRACKED
TRACKED="$(git ls-files)"

missing_paths=$(printf '%s\n' "$maintained_docs" | xargs awk '
BEGIN {
    n = split(ENVIRON["TRACKED"], t, "\n")
    for (i = 1; i <= n; i++) {
        if (t[i] == "") continue
        known[t[i]] = 1
        p = t[i]
        while (sub(/\/[^\/]*$/, "", p)) known[p] = 1
    }
}
function dirOf(p,   i) {
    i = length(p)
    while (i > 0 && substr(p, i, 1) != "/") i--
    return (i == 0) ? "" : substr(p, 1, i - 1)
}
function normalize(p,   c, seg, i, out, k, joined) {
    c = split(p, seg, "/")
    k = 0
    for (i = 1; i <= c; i++) {
        if (seg[i] == "" || seg[i] == ".") continue
        if (seg[i] == "..") { if (k > 0) k--; continue }
        out[++k] = seg[i]
    }
    joined = ""
    for (i = 1; i <= k; i++) joined = (i == 1) ? out[i] : joined "/" out[i]
    return joined
}
# Placeholders, globs and shell expansions are not claims about a path.
function unjudgeable(p) { return p == "" || p ~ /[<>*?${}`|"()]/ || p ~ /\.\.\./ }
function emit(path, kind) {
    if (path in known) return
    print "  " FILENAME ":" FNR "  " path "  (" kind ")"
}
FNR == 1 { infence = 0; treed = 0; root = "" }
/guard:planned/ { next }
/^[ \t]*(```|~~~)/ {
    if (infence) { infence = 0; treed = 0; root = ""; next }
    infence = 1; expectroot = 1; next
}
infence {
    if (expectroot) {
        expectroot = 0
        anchor = $0
        sub(/[ \t].*$/, "", anchor)
        if (anchor == "." || anchor == "./") { root = ""; treed = 1 }
        else if (anchor ~ /^[A-Za-z0-9._][A-Za-z0-9._\/-]*\/$/) {
            anchor = normalize(anchor)
            if (anchor in known) { root = anchor; treed = 1 }
        }
        next
    }
    if (!treed || $0 !~ /[├└]── /) next
    # `│   ` or four spaces is one indent level, counted with gsub so that no
    # locale-dependent length() is ever applied to a multi-byte glyph.
    prefix = $0
    sub(/[├└]── .*$/, "", prefix)
    depth = gsub(/│   /, "", prefix) + gsub(/    /, "", prefix)
    name = $0
    sub(/^.*[├└]── /, "", name)
    sub(/[ \t].*$/, "", name)
    sub(/\/$/, "", name)
    if (unjudgeable(name)) next
    stack[depth] = name
    path = root
    for (d = 0; d <= depth; d++) path = (path == "") ? stack[d] : path "/" stack[d]
    emit(normalize(path), "structure block")
    next
}
# Markdown links, outside fences only — inside one the syntax is literal text.
{
    rest = $0
    while (match(rest, /\]\([^)" ]+\)/)) {
        target = substr(rest, RSTART + 2, RLENGTH - 3)
        rest = substr(rest, RSTART + RLENGTH)
        sub(/#.*$/, "", target)
        if (target ~ /^(https?|mailto|ftp|file):/) continue
        if (target ~ /^[#\/~]/) continue
        if (unjudgeable(target)) continue
        base = dirOf(FILENAME)
        emit(normalize((base == "") ? target : base "/" target), "link")
    }
}
')

if [ -n "$missing_paths" ]; then
  report "a maintained document names a repository path that does not exist:"$'\n'"$missing_paths"
else
  pass "every repository path named in a maintained document exists"
fi

# ---------------------------------------------------------------------------
# 5. A Korean twin must not fall behind the English page it was translated from.
#    Bilingual docs were rejected once (2026-08-04-english-only-artifacts option B)
#    on exactly one ground — "every page twice; guaranteed drift" — and the
#    reversal only holds while something mechanical catches the drift. This is
#    that something.
#
#    Each `<name>.ko.md` carries the blob hash of its source on its first line:
#        <!-- translated-from: README.md@<40 hex> -->
#    Editing the English page without touching the twin changes the hash and
#    fails the build.
#
#    HASHES, NOT HISTORY. A guard comparing commit ancestry would need the log,
#    and CI checks out at fetch-depth 1 — it would pass vacuously on every
#    shallow clone, which is precisely how the English-only check itself was
#    broken for weeks (#123). A blob hash needs no history at all.
#
#    Read from the index, like the path check above, so a dirty workspace cannot
#    make it pass. Twins opt in by existing, so translations can land one at a
#    time.
#
#    It catches forgetting, not lying: the hash can be bumped without
#    translating anything, exactly as `Wiki-Skip:` can carry any reason.
# ---------------------------------------------------------------------------
stale_twins=""
missing_marker=""
for twin in $(git ls-files '*.ko.md'); do
  source_page="${twin%.ko.md}.md"
  if ! git ls-files --error-unmatch "$source_page" >/dev/null 2>&1; then
    missing_marker="$missing_marker"$'\n'"  $twin  names $source_page, which is not tracked"
    continue
  fi
  declared=$(git show ":$twin" | sed -n \
    's|.*translated-from: *[^@]*@\([0-9a-f]\{40\}\).*|\1|p' | head -1)
  actual=$(git rev-parse ":$source_page")
  if [ -z "$declared" ]; then
    missing_marker="$missing_marker"$'\n'"  $twin  has no <!-- translated-from: $source_page@<sha> --> line"
  elif [ "$declared" != "$actual" ]; then
    stale_twins="$stale_twins"$'\n'"  $source_page changed since $twin was written (expected $actual, twin says $declared)"
  fi
done

if [ -n "$missing_marker" ]; then
  report "a Korean twin cannot say what it was translated from:$missing_marker"
elif [ -n "$stale_twins" ]; then
  report "a Korean twin is behind its English source:$stale_twins"$'\n'"  Update the translation, then set its translated-from hash to the value above."
else
  pass "every Korean twin matches the English page it names"
fi

# ---------------------------------------------------------------------------
# 6. Every wiki `sources:` entry must resolve to a tracked file.
#    The wiki schema (docs/llm-wiki/CLAUDE.md §1) calls raw/ the source of truth
#    and wiki/sources/ the traceability anchor, which only holds if the citation
#    points at something. Two ADRs cited raw sessions that were never written —
#    `2026-08-07-adversarial-review.md` and `2026-08-10-sensor-verified.md` — and
#    a citation makes a page look traceable whether or not the file exists, so
#    nothing inside the wiki could show the drift.
#
#    Two entry forms, both in use: a path ending in `.md` is relative to
#    docs/llm-wiki/, and a bare `decisions/x` is a wiki page without its suffix.
#
#    Scope is deliberately just `sources:`. Checking `[[...]]` targets too would
#    have to special-case the schema document, which uses `[[concepts/foo]]` as
#    an example — a guard with an exception list is the kind that gets muted.
# ---------------------------------------------------------------------------
resolve_source() {
  case "$1" in
    *.md) printf 'docs/llm-wiki/%s' "$1" ;;
    *) printf 'docs/llm-wiki/wiki/%s.md' "$1" ;;
  esac
}

# Read from the index, not the worktree: an uncommitted raw session would
# otherwise satisfy a citation that lands in the same commit without it.
dangling_sources=""
cited=0
for page in $(git ls-files 'docs/llm-wiki/wiki/*.md'); do
  entries=$(git show ":$page" | sed -n '1,12{s/^sources: *\[\(.*\)\] *$/\1/p;}' | tr ',' '\n')
  for entry in $entries; do
    cited=$((cited + 1))
    target=$(resolve_source "$entry")
    git ls-files --error-unmatch "$target" >/dev/null 2>&1 && continue
    dangling_sources="$dangling_sources"$'\n'"  ${page#docs/llm-wiki/}  cites $entry ($target is not tracked)"
  done
done

# A `sources:` line that stopped parsing would report a clean wiki forever, which
# is the failure this guard exists to catch, one level up (#123, ADR
# 2026-08-10-guards-must-prove-they-ran). So prove the extractor found citations
# and that resolution actually rejects something before trusting its silence.
if [ "$cited" -eq 0 ]; then
  report "the wiki source check parsed no citations at all — it would pass any wiki"
elif git ls-files --error-unmatch "$(resolve_source 'raw/sessions/never-written.md')" >/dev/null 2>&1; then
  report "the wiki source check resolves a path that does not exist — it would pass any citation"
elif [ -n "$dangling_sources" ]; then
  report "a wiki page cites a source that is not in the repository:$dangling_sources"$'\n'"  Write the raw session, or drop the citation. Do not reconstruct a source from the page citing it."
else
  pass "all $cited wiki source citations resolve"
fi

# ---------------------------------------------------------------------------
# 7. Every log.md ingest line has a raw session of that date.
#    The original failure (#161): `log.md` recorded 33 ingests while
#    raw/sessions/ had been empty since 2026-08-05. The log line is the visible
#    half of the ritual so it kept being copied; saving the raw has no immediate
#    consumer, so it stopped. Guard §6 catches a citation pointing at nothing —
#    it cannot catch a claim with nothing beside it.
#
#    A date is matched from a raw session's FILENAME or its H1, because a day's
#    work does not always get its own file: 2026-08-08 is one question, recorded
#    inside a page named for 2026-08-10 whose H1 reads `# 2026-08-08 / 2026-08-10`.
#    Filename-or-heading is as much prose as this is willing to read.
#
#    No date floor, and that was checked rather than assumed — the #163 back-fill
#    left every ingest date covered, so the rule applies to the whole file. An
#    exemption is what a rule decays into.
# ---------------------------------------------------------------------------
ingest_dates=$(git show ":docs/llm-wiki/log.md" \
  | grep -oE '^## \[[0-9]{4}-[0-9]{2}-[0-9]{2}\] ingest' \
  | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | sort -u)

# Filenames and first-line headings, both read from the index like the checks above,
# so an uncommitted raw session cannot satisfy a log line that lands without it.
raw_dates=$(
  for raw in $(git ls-files 'docs/llm-wiki/raw/sessions/*.md'); do
    basename "$raw"
    git show ":$raw" | sed -n '1{/^# /p;}'
  done | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | sort -u
)

unbacked=""
for date in $ingest_dates; do
  printf '%s\n' "$raw_dates" | grep -qx "$date" && continue
  unbacked="$unbacked"$'\n'"  $date  is logged as ingested and has no raw session"
done

# An extraction that quietly stopped matching would report a clean wiki forever —
# the #123 failure one level over. Prove both halves before trusting the silence.
if [ -z "$ingest_dates" ]; then
  report "the ingest-log check parsed no ingest dates at all — it would pass any wiki"
elif [ -z "$raw_dates" ]; then
  report "the ingest-log check found no raw session dates — it would fail every wiki"
elif printf '%s\n' "$raw_dates" | grep -qx "1999-01-01"; then
  report "the ingest-log check matches a date that is not there — it would pass any log line"
elif [ -n "$unbacked" ]; then
  report "a log.md ingest line claims a session that was never saved:$unbacked"$'\n'"  Write the raw session, or drop the log line. The log entry is not the record."
else
  pass "every log.md ingest date has a raw session ($(printf '%s\n' "$ingest_dates" | wc -l | tr -d ' ') dates)"
fi

printf '\n'
if [ "$fail" -ne 0 ]; then
  echo "guards: FAILED"
  exit 1
fi
echo "guards: all checks passed"
