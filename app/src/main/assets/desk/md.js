// Markdown → HTML for Claude's replies, with light syntax colouring. Everything from the model is escaped: no raw HTML
// gets through (the page can talk to the app, so model output must never become markup or script).
"use strict";

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function safeUrl(u) {
  u = u.trim();
  return /^(https?:\/\/|mailto:|#)/i.test(u) ? u : null;
}

/* ------------------------------------------------------------------ inline */
function inline(src) {
  let out = "";
  let i = 0;
  const n = src.length;
  let text = "";
  const flush = () => { if (text) { out += autolink(esc(text)); text = ""; } };
  while (i < n) {
    const c = src[i];
    if (c === "\\" && i + 1 < n && /[\\`*_{}\[\]()#+\-.!~|>]/.test(src[i + 1])) { text += src[i + 1]; i += 2; continue; }
    if (c === "`") {
      let ticks = 1; while (src[i + ticks] === "`") ticks++;
      const fence = "`".repeat(ticks);
      const end = src.indexOf(fence, i + ticks);
      if (end > 0) {
        flush();
        let code = src.slice(i + ticks, end);
        if (/^ .* $/.test(code)) code = code.slice(1, -1);
        out += "<code>" + esc(code) + "</code>";
        i = end + ticks; continue;
      }
    }
    if (c === "[") {
      const m = /^\[([^\]\n]*(?:\[[^\]\n]*\][^\]\n]*)*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/.exec(src.slice(i));
      if (m) {
        const url = safeUrl(m[2]);
        flush();
        out += url ? '<a href="' + esc(url) + '" data-ext="1">' + inline(m[1]) + "</a>" : inline(m[1]);
        i += m[0].length; continue;
      }
    }
    if ((c === "*" || c === "_") && src[i + 1] === c) {
      const end = src.indexOf(c + c, i + 2);
      if (end > i + 2 && src[i + 2] !== " ") { flush(); out += "<strong>" + inline(src.slice(i + 2, end)) + "</strong>"; i = end + 2; continue; }
    }
    if (c === "~" && src[i + 1] === "~") {
      const end = src.indexOf("~~", i + 2);
      if (end > i + 2) { flush(); out += "<del>" + inline(src.slice(i + 2, end)) + "</del>"; i = end + 2; continue; }
    }
    if ((c === "*" || c === "_") && src[i + 1] !== " " && src[i + 1] !== c) {
      // _ only at word edges (snake_case stays as it is)
      const okStart = c === "*" || i === 0 || /[\s(\[]/.test(src[i - 1]);
      if (okStart) {
        let end = i + 1;
        while ((end = src.indexOf(c, end)) > 0 && (src[end - 1] === " " || src[end + 1] === c)) end++;
        if (end > i + 1 && (c === "*" || end + 1 >= n || /[\s.,;:!?)\]]/.test(src[end + 1]))) {
          flush(); out += "<em>" + inline(src.slice(i + 1, end)) + "</em>"; i = end + 1; continue;
        }
      }
    }
    if (c === "\n") { flush(); out += "<br>"; i++; continue; }
    text += c; i++;
  }
  flush();
  return out;
}

function autolink(html) {
  return html.replace(/\bhttps?:\/\/[^\s<>"')\]]+[^\s<>"')\].,;:!?]/g, (u) => '<a href="' + u + '" data-ext="1">' + u + "</a>");
}

/* ------------------------------------------------------------------ blocks */
function renderMarkdown(src) {
  const lines = String(src || "").replace(/\r\n?/g, "\n").split("\n");
  return blocks(lines);
}

function blocks(lines) {
  let html = "";
  let i = 0;
  const isFence = (l) => /^\s{0,3}(```|~~~)/.test(l);
  const isHr = (l) => /^\s{0,3}([-*_])(\s*\1){2,}\s*$/.test(l);
  const isHead = (l) => /^\s{0,3}#{1,6}\s/.test(l);
  const isQuote = (l) => /^\s{0,3}>/.test(l);
  const isList = (l) => /^\s*([-*+]|\d{1,9}[.)])\s+/.test(l);
  const isTableSep = (l) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l);
  const startsBlock = (l) => isFence(l) || isHr(l) || isHead(l) || isQuote(l) || isList(l);

  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }

    if (isFence(line)) {
      const m = /^\s{0,3}(```+|~~~+)\s*([^\s`]*)/.exec(line);
      const fence = m[1];
      const lang = m[2] || "";
      const body = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith(fence)) body.push(lines[i++]);
      i++;
      html += codeBlock(body.join("\n"), lang);
      continue;
    }
    if (isHead(line)) {
      const m = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line);
      html += "<h" + m[1].length + ">" + inline(m[2]) + "</h" + m[1].length + ">";
      i++; continue;
    }
    if (isHr(line)) { html += "<hr>"; i++; continue; }
    if (isQuote(line)) {
      const body = [];
      while (i < lines.length && lines[i].trim() && (isQuote(lines[i]) || !startsBlock(lines[i]))) {
        body.push(lines[i].replace(/^\s{0,3}>\s?/, "")); i++;
      }
      html += "<blockquote>" + blocks(body) + "</blockquote>";
      continue;
    }
    if (isList(line)) {
      const r = list(lines, i);
      html += r.html; i = r.next; continue;
    }
    if (line.includes("|") && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const r = table(lines, i);
      html += r.html; i = r.next; continue;
    }
    const para = [];
    while (i < lines.length && lines[i].trim() && !startsBlock(lines[i])
      && !(lines[i].includes("|") && i + 1 < lines.length && isTableSep(lines[i + 1]))) para.push(lines[i++]);
    html += "<p>" + inline(para.join("\n").trim()) + "</p>";
  }
  return html;
}

function list(lines, start) {
  const indentOf = (l) => l.match(/^\s*/)[0].replace(/\t/g, "    ").length;
  const base = indentOf(lines[start]);
  const ordered = /^\s*\d/.test(lines[start]);
  const first = ordered ? parseInt(lines[start].trim(), 10) : 1;
  let html = ordered ? (first !== 1 ? '<ol start="' + first + '">' : "<ol>") : "<ul>";
  let i = start;
  while (i < lines.length) {
    const l = lines[i];
    if (!l.trim()) {
      // a blank line ends the list unless the next line continues it
      if (i + 1 < lines.length && (indentOf(lines[i + 1]) > base || /^\s*([-*+]|\d{1,9}[.)])\s+/.test(lines[i + 1]) && indentOf(lines[i + 1]) === base)) { i++; continue; }
      break;
    }
    const m = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/.exec(l);
    if (!m || indentOf(l) !== base) {
      if (indentOf(l) > base || (m && indentOf(l) > base)) break; // handled by the item below
      break;
    }
    const item = [m[3]];
    i++;
    while (i < lines.length && (lines[i].trim() === "" ? (i + 1 < lines.length && indentOf(lines[i + 1]) > base) : indentOf(lines[i]) > base)) {
      item.push(lines[i].slice(Math.min(indentOf(lines[i]), base + 2)));
      i++;
    }
    let text = item[0];
    let task = "";
    const t = /^\[([ xX])\]\s+(.*)$/.exec(text);
    if (t) { task = '<input type="checkbox" disabled' + (t[1] !== " " ? " checked" : "") + ">"; text = t[2]; }
    const rest = item.slice(1);
    const sub = rest.length ? blocks(rest) : "";
    html += "<li>" + task + inline(text) + sub + "</li>";
  }
  html += ordered ? "</ol>" : "</ul>";
  return { html, next: i };
}

function table(lines, start) {
  const cells = (l) => l.trim().replace(/^\|/, "").replace(/\|$/, "").split(/(?<!\\)\|/).map((c) => c.trim());
  const head = cells(lines[start]);
  const aligns = cells(lines[start + 1]).map((c) => (/^:-+:$/.test(c) ? "center" : /-+:$/.test(c) ? "right" : ""));
  let html = "<table><thead><tr>" + head.map((c, k) => "<th" + (aligns[k] ? ' style="text-align:' + aligns[k] + '"' : "") + ">" + inline(c) + "</th>").join("") + "</tr></thead><tbody>";
  let i = start + 2;
  while (i < lines.length && lines[i].includes("|") && lines[i].trim()) {
    const row = cells(lines[i]);
    html += "<tr>" + head.map((_, k) => "<td" + (aligns[k] ? ' style="text-align:' + aligns[k] + '"' : "") + ">" + inline(row[k] || "") + "</td>").join("") + "</tr>";
    i++;
  }
  return { html: html + "</tbody></table>", next: i };
}

function codeBlock(code, lang) {
  return '<div class="codeblock"><div class="cb-head"><span>' + esc(lang || "text") + '</span><button class="copy-btn" data-copy="1">'
    + '<svg class="i" viewBox="0 0 24 24">' + ICONS.copy + "</svg>复制</button></div><pre><code>" + highlight(code, lang) + "</code></pre></div>";
}

/* ------------------------------------------------------------------ highlighting */
const KW = {
  js: "async await break case catch class const continue debugger default delete do else export extends finally for from function if import in instanceof let new of return static super switch this throw try typeof var void while with yield null undefined true false interface type enum implements private public protected readonly as",
  py: "and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield None True False self match case",
  java: "abstract boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public return short static super switch synchronized this throw throws try void volatile while null true false var val fun object when override data sealed",
  c: "auto break case char const continue default do double else enum extern float for goto if int long register return short signed sizeof static struct switch typedef union unsigned void volatile while include define ifdef ifndef endif nullptr true false class public private protected namespace template typename using new delete virtual override",
  go: "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var nil true false",
  rs: "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while",
  sh: "if then else elif fi for while until do done case esac function in return local export set unset echo cd exit source alias read shift",
  ps: "function param begin process end if else elseif foreach for while do until switch return break continue try catch finally throw trap",
  sql: "select from where and or not insert into values update set delete create table drop alter index join left right inner outer on group by order having limit as distinct union null is in like between case when then else end primary key",
  css: "",
  json: "true false null",
};
const LANG = {
  javascript: "js", js: "js", jsx: "js", ts: "js", tsx: "js", typescript: "js", mjs: "js", json: "json", jsonc: "json",
  python: "py", py: "py", java: "java", kotlin: "java", kt: "java", c: "c", cpp: "c", "c++": "c", h: "c", cs: "java", csharp: "java",
  go: "go", golang: "go", rust: "rs", rs: "rs", bash: "sh", sh: "sh", shell: "sh", zsh: "sh", console: "sh",
  powershell: "ps", ps1: "ps", pwsh: "ps", sql: "sql", css: "css", scss: "css", html: "html", xml: "html", svg: "html", yaml: "yaml", yml: "yaml", toml: "yaml", ini: "yaml",
};

function highlight(code, lang) {
  const L = LANG[(lang || "").toLowerCase()];
  if (!L) return esc(code);
  if (L === "html") return esc(code).replace(/(&lt;\/?)([\w-]+)/g, '$1<span class="hl-k">$2</span>').replace(/([\w-]+)=(&quot;.*?&quot;)/g, '<span class="hl-f">$1</span>=<span class="hl-s">$2</span>');
  if (L === "yaml") return esc(code).replace(/^(\s*-?\s*)([\w.-]+)(:)/gm, '$1<span class="hl-f">$2</span>$3').replace(/(#.*)$/gm, '<span class="hl-c">$1</span>');
  const kw = new Set(KW[L].split(" "));
  const hash = L === "py" || L === "sh" || L === "ps" || L === "yaml";
  const re = new RegExp([
    hash ? "#.*" : "\\/\\/.*",
    "\\/\\*[\\s\\S]*?\\*\\/",
    L === "py" ? '"""[\\s\\S]*?"""|\'\'\'[\\s\\S]*?\'\'\'' : "(?!)",
    L === "sql" ? "--.*" : "(?!)",
    '"(?:\\\\.|[^"\\\\\\n])*"',
    "'(?:\\\\.|[^'\\\\\\n])*'",
    L === "js" || L === "go" ? "`(?:\\\\.|[^`\\\\])*`" : "(?!)",
    "\\b\\d[\\d_]*(?:\\.\\d+)?(?:e[+-]?\\d+)?\\b|\\b0x[\\da-f]+\\b",
    L === "sh" || L === "ps" ? "\\$\\{?[\\w:]+\\}?" : "(?!)",
    "[A-Za-z_][\\w]*",
  ].map((p) => "(" + p + ")").join("|"), "gi");
  let out = "", last = 0, m;
  while ((m = re.exec(code))) {
    out += esc(code.slice(last, m.index));
    const t = m[0];
    let cls = "";
    if (m[1] || m[2] || m[3] || m[4]) cls = "c";
    else if (m[5] || m[6] || m[7]) cls = "s";
    else if (m[8]) cls = "n";
    else if (m[9]) cls = "t";
    else if (m[10]) {
      if (kw.has(L === "sql" ? t.toLowerCase() : t)) cls = "k";
      else if (code[re.lastIndex] === "(") cls = "f";
      else if (/^[A-Z][a-z]/.test(t) && L !== "sh") cls = "t";
    }
    out += cls ? '<span class="hl-' + cls + '">' + esc(t) + "</span>" : esc(t);
    last = re.lastIndex;
    if (m[0].length === 0) re.lastIndex++;
  }
  return out + esc(code.slice(last));
}
