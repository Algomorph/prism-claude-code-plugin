/* Prism hybrid chat-shell — browser-side runtime.
 *
 * The ONLY code that turns transcript content into DOM. Runs inside JCEF. Kotlin sends a
 * base64 TranscriptDelta of typed blocks (never HTML, R18); this file renders + sanitizes.
 *
 * Security pipeline per markdown block (design §6.8):
 *   marked (with a math tokenizer extension, not regex pre-passes — §7) -> DOMPurify
 *   (pinned, allowlist) -> harden links/images -> replace math placeholders with TRUSTED
 *   KaTeX widgets built AFTER sanitize (so hostile text can never forge one).
 *
 * Math is a real marked tokenizer token that only fills gaps marked's own code rules leave
 * (fenced/inline code is consumed first), so `$` inside code is never math. The raw matched
 * source (incl. original delimiters) is preserved for byte-exact copy.
 *
 * Host-injected globals (all optional; calls guarded):
 *   window.__prismAck(json)      — post a render/layout ack (JBCefJSQuery).
 *   window.__prismLink(href)     — open an http(s) link in the OS browser.
 *   window.__prismCopy(text)     — copy text via the IDE clipboard; host replies
 *                                   window.__prismCopyDone(ok) so "Copied" is truthful.
 */
(function () {
    "use strict";

    var DP = window.DOMPurify;

    var SANITIZE_CONFIG = {
        ALLOWED_TAGS: [
            "p", "br", "hr", "span", "div",
            "strong", "em", "b", "i", "u", "s", "del", "ins", "mark", "sub", "sup",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "dl", "dt", "dd",
            "blockquote", "pre", "code",
            "a", "img",
            "table", "thead", "tbody", "tr", "th", "td"
        ],
        ALLOWED_ATTR: ["href", "title", "src", "alt", "class", "colspan", "rowspan", "align"],
        FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "svg", "math", "form", "input", "template"],
        FORBID_ATTR: ["onerror", "onload", "onclick", "onmouseover", "style"],
        ALLOW_DATA_ATTR: false
    };

    var SAFE_LINK_SCHEMES = /^(https?):/i;

    function decodeB64Utf8(b64) {
        var bin = atob(b64);
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new TextDecoder("utf-8").decode(bytes);
    }

    // --- math tokenizer (marked extension) -------------------------------------
    // Per-parse store of captured math; index encoded into an inert <code> placeholder
    // that survives DOMPurify, then swapped for a trusted KaTeX widget post-sanitize.
    var mathStore = [];

    function mathPlaceholder(tex, display, raw) {
        var idx = mathStore.length;
        mathStore.push({ tex: tex, display: display, raw: raw });
        return '<code class="__pm">' + idx + "</code>";
    }

    var blockMath = {
        name: "prismBlockMath",
        level: "block",
        start: function (src) { var i = src.indexOf("$$"); return i < 0 ? undefined : i; },
        tokenizer: function (src) {
            var m = /^\$\$([\s\S]+?)\$\$/.exec(src) || /^\\\[([\s\S]+?)\\\]/.exec(src);
            if (m) return { type: "prismBlockMath", raw: m[0], text: m[1] };
        },
        renderer: function (t) { return mathPlaceholder(t.text, true, t.raw); }
    };

    var inlineMath = {
        name: "prismInlineMath",
        level: "inline",
        start: function (src) { var i = src.search(/[\$\\]/); return i < 0 ? undefined : i; },
        tokenizer: function (src) {
            // \( … \) always math.
            var p = /^\\\(([\s\S]+?)\\\)/.exec(src);
            if (p) return { type: "prismInlineMath", raw: p[0], text: p[1] };
            // $ … $ with a conservative currency-avoiding grammar: no space just inside the
            // delimiters, closing $ not immediately followed by a digit ("$5 and $10").
            var m = /^\$(?!\s)((?:\\.|[^\$\\\n])+?)(?<!\s)\$(?!\d)/.exec(src);
            if (m) return { type: "prismInlineMath", raw: m[0], text: m[1] };
        },
        renderer: function (t) { return mathPlaceholder(t.text, false, t.raw); }
    };

    if (window.marked && window.marked.use) {
        window.marked.use({ extensions: [blockMath, inlineMath] });
    }

    function renderKatex(tex, display) {
        return window.katex.renderToString(tex, {
            displayMode: display, throwOnError: false, strict: false, trust: false
        });
    }

    function buildWidget(entry) {
        var wrap = document.createElement("span");
        wrap.className = "prism-math " + (entry.display ? "prism-math--block" : "prism-math--inline");
        wrap.tabIndex = 0;
        wrap.setAttribute("role", "button");
        wrap.setAttribute("aria-label", "Math — click to show LaTeX source");

        var source = document.createElement("span");
        source.className = "prism-math__source";
        var copy = document.createElement("span");
        copy.className = "prism-math__copy";
        copy.setAttribute("role", "button");
        copy.title = "Copy LaTeX";
        copy.textContent = "Copy";
        // Bind the clipboard bridge DIRECTLY to this host-created node (security §6.8,
        // review #2). Delegated document handlers keyed on a class name could be triggered
        // by hostile markdown forging class="prism-math__copy"; a direct listener on a node
        // WE built cannot be forged.
        copy.addEventListener("click", function (e) { e.stopPropagation(); doCopy(copy); });
        var code = document.createElement("span");
        code.className = "prism-math__code";
        code.textContent = entry.raw; // byte-exact original source incl. delimiters (§7)
        source.appendChild(copy);
        source.appendChild(code);

        var render = document.createElement("span");
        render.className = "prism-math__render";
        try {
            render.innerHTML = renderKatex(entry.tex, entry.display); // trusted KaTeX output
        } catch (e) {
            var er = document.createElement("code");
            er.className = "math-error";
            er.textContent = entry.tex;
            render.appendChild(er);
        }
        wrap.appendChild(source);
        wrap.appendChild(render);
        return wrap;
    }

    function swapMathPlaceholders(root) {
        var phs = root.querySelectorAll ? root.querySelectorAll("code.__pm") : [];
        for (var i = 0; i < phs.length; i++) {
            var idx = parseInt(phs[i].textContent, 10);
            var entry = mathStore[idx];
            if (entry) phs[i].parentNode.replaceChild(buildWidget(entry), phs[i]);
        }
    }

    // --- sanitize + harden -----------------------------------------------------
    function renderMarkdownToClean(md) {
        mathStore = [];
        var html = window.marked ? window.marked.parse(md == null ? "" : md, { gfm: true, breaks: false }) : String(md);
        return DP.sanitize(html, SANITIZE_CONFIG);
    }

    function hardenNode(root) {
        var links = root.querySelectorAll ? root.querySelectorAll("a[href]") : [];
        for (var i = 0; i < links.length; i++) {
            (function (a) {
                var href = a.getAttribute("href") || "";
                if (SAFE_LINK_SCHEMES.test(href)) {
                    a.addEventListener("click", function (e) {
                        e.preventDefault();
                        if (window.__prismLink) { try { window.__prismLink(href); } catch (x) {} }
                    });
                } else {
                    a.removeAttribute("href");
                    a.classList.add("prism-link--blocked");
                }
            })(links[i]);
        }
        // Every <img> reaching us via MARKDOWN is replaced with a neutral marker (review
        // #2). Legitimate transcript images arrive as typed `image` blocks that renderBlock
        // builds from a MediaResolver-validated data URI — they never pass through here — so
        // markdown-authored images (which bypass the resolver, its size/pixel caps, and its
        // SVG rejection) must not be dereferenced by the browser.
        var imgs = root.querySelectorAll ? root.querySelectorAll("img") : [];
        for (var j = 0; j < imgs.length; j++) {
            var marker = document.createElement("span");
            marker.className = "prism-image--blocked";
            marker.textContent = imgs[j].getAttribute("alt") || "[blocked image]";
            imgs[j].parentNode.replaceChild(marker, imgs[j]);
        }
        return root;
    }

    // Render markdown (with math + hardening) into a target element.
    function renderMarkdownInto(el, md) {
        el.innerHTML = renderMarkdownToClean(md);
        swapMathPlaceholders(el);
        hardenNode(el);
        return el;
    }

    // --- block + message rendering ---------------------------------------------
    function renderBlock(block) {
        var el = document.createElement("div");
        el.className = "prism-block prism-block--" + (block.kind || "unknown") +
            " prism-vis--" + (block.visibility || "visible");
        switch (block.kind) {
            case "text":
            case "thinking": {
                var body = document.createElement("div");
                body.className = "prism-block__body";
                renderMarkdownInto(body, block.markdown || "");
                el.appendChild(body);
                break;
            }
            case "toolUse": {
                var t = document.createElement("div");
                t.className = "prism-tool";
                var name = document.createElement("span");
                name.className = "prism-tool__name";
                name.textContent = block.toolName || "tool";
                t.appendChild(name);
                if (block.toolInput) {
                    var inp = document.createElement("pre");
                    inp.className = "prism-tool__input";
                    inp.textContent = block.toolInput;
                    t.appendChild(inp);
                }
                el.appendChild(t);
                break;
            }
            case "toolResult": {
                var r = document.createElement("pre");
                r.className = "prism-tool__result" + (block.isError ? " prism-tool__result--error" : "");
                r.textContent = block.toolResultText || "";
                el.appendChild(r);
                break;
            }
            case "image": {
                if (block.imageDataUri && block.imageDataUri.indexOf("data:image/") === 0) {
                    var img = document.createElement("img");
                    img.src = block.imageDataUri;
                    img.alt = block.imageAlt || "image";
                    img.className = "prism-image";
                    el.appendChild(img);
                } else {
                    var m = document.createElement("span");
                    m.className = "prism-image--blocked";
                    m.textContent = block.imageAlt || "[blocked image]";
                    el.appendChild(m);
                }
                break;
            }
            case "compactBoundary": {
                var div = document.createElement("div");
                div.className = "prism-compact-divider";
                div.textContent = block.label || "Conversation compacted";
                el.appendChild(div);
                break;
            }
            default: {
                var u = document.createElement("span");
                u.className = block.kind === "toolReference" ? "prism-tool-ref" : "prism-unsupported";
                u.textContent = block.label || "[unsupported content]";
                el.appendChild(u);
            }
        }
        return el;
    }

    function labelFor(kind) {
        var L = window.__prismLabels || {};
        if (kind === "thinking") return L.thinking || "Thinking";
        if (kind === "toolResult") return L.output || "Output";
        return L.details || "Details";
    }

    // Wrap a collapsed-class block in a native <details> disclosure (keyboard-accessible).
    function wrapCollapsed(inner, kind) {
        var d = document.createElement("details");
        d.className = "prism-collapsed";
        var s = document.createElement("summary");
        s.textContent = labelFor(kind);
        d.appendChild(s);
        d.appendChild(inner);
        return d;
    }

    function isToolKind(kind) {
        return kind === "toolUse" || kind === "toolResult" || kind === "toolReference";
    }

    function renderMessage(id, payload) {
        var msg = document.createElement("div");
        msg.className = "prism-msg prism-msg--" + (payload.role || "assistant");
        msg.setAttribute("data-prism-id", id);
        var blocks = payload.blocks || [];
        // Show the "You"/"Claude" header only when the message carries primary content.
        // A message that is purely tool call/result/reference (e.g. a user record that only
        // holds a tool_result) gets no naked role header above its disclosure (review #5).
        var hasPrimary = false;
        for (var p = 0; p < blocks.length; p++) {
            if (blocks[p].visibility !== "hidden-internal" && !isToolKind(blocks[p].kind)) { hasPrimary = true; break; }
        }
        if (hasPrimary) {
            var role = document.createElement("div");
            role.className = "prism-msg__role";
            role.textContent = payload.roleLabel || payload.role || "";
            msg.appendChild(role);
        }
        for (var i = 0; i < blocks.length; i++) {
            if (blocks[i].visibility === "hidden-internal") continue;
            var node = renderBlock(blocks[i]);
            if (blocks[i].visibility === "collapsed") node = wrapCollapsed(node, blocks[i].kind);
            msg.appendChild(node);
        }
        return msg;
    }

    // Show/replace a status banner (loading, no-transcript, unavailable, reconnecting,
    // error) so these states are never a blank pane (review #4). Empty text clears it.
    window.__prismSetStatus = function (b64) {
        var text = "";
        try { text = b64 ? decodeB64Utf8(b64) : ""; } catch (e) { text = ""; }
        var el = document.getElementById("prism-status");
        if (!text) { if (el && el.parentNode) el.parentNode.removeChild(el); return; }
        if (!el) {
            el = document.createElement("div");
            el.id = "prism-status";
            el.className = "prism-status";
            el.setAttribute("role", "status");
            document.body.insertBefore(el, document.body.firstChild);
        }
        el.textContent = text;
    };

    // In-place theme patch (design §10): update CSS variables on :root, no reload.
    window.__prismSetTheme = function (b64) {
        try {
            var vars = JSON.parse(decodeB64Utf8(b64));
            var root = document.documentElement;
            for (var k in vars) if (vars.hasOwnProperty(k)) root.style.setProperty(k, vars[k]);
        } catch (e) { /* ignore malformed theme */ }
    };

    // --- delta application -----------------------------------------------------
    var state = { epoch: -1, revision: -1 };
    function contentEl() { return document.getElementById("prism-content"); }
    function cssEscape(s) { return String(s).replace(/["\\]/g, "\\$&"); }

    function applyOperation(op) {
        var root = contentEl();
        if (op.op === "reset") { root.innerHTML = ""; return; }
        if (op.op === "remove") {
            var gone = root.querySelector('[data-prism-id="' + cssEscape(op.id) + '"]');
            if (gone) gone.parentNode.removeChild(gone);
            return;
        }
        if (op.op === "upsert") {
            var node = renderMessage(op.id, op.payload || {});
            var existing = root.querySelector('[data-prism-id="' + cssEscape(op.id) + '"]');
            if (existing) existing.parentNode.replaceChild(node, existing);
            else root.appendChild(node);
        }
    }

    function atBottom() {
        var d = document.documentElement;
        return (d.scrollHeight - d.scrollTop - d.clientHeight) < 40;
    }

    window.__prismApplyDelta = function (b64) {
        var status = "ok", epoch = state.epoch, revision = state.revision;
        try {
            var delta = JSON.parse(decodeB64Utf8(b64));
            epoch = delta.epoch; revision = delta.revision;
            if (delta.epoch !== state.epoch) contentEl().innerHTML = "";
            var pin = atBottom();
            var ops = delta.operations || [];
            for (var i = 0; i < ops.length; i++) applyOperation(ops[i]);
            state.epoch = delta.epoch; state.revision = delta.revision;
            if (pin) window.scrollTo(0, document.documentElement.scrollHeight);
        } catch (e) {
            status = "error:" + (e && e.message ? e.message : "unknown");
        }
        var ack = function () {
            if (window.__prismAck) {
                try { window.__prismAck(JSON.stringify({ epoch: epoch, revision: revision, status: status })); } catch (x) {}
            }
        };
        if (window.requestAnimationFrame) window.requestAnimationFrame(function () { window.requestAnimationFrame(ack); });
        else ack();
        return status;
    };

    // --- click-to-reveal-source + byte-exact copy interaction ------------------
    var pendingCopyBtn = null;

    function collapseAll() {
        var open = document.querySelectorAll(".prism-math.is-open");
        for (var i = 0; i < open.length; i++) open[i].classList.remove("is-open");
    }

    function flashCopied(btn) {
        btn.classList.add("is-copied");
        btn.textContent = "Copied";
        setTimeout(function () { btn.classList.remove("is-copied"); btn.textContent = "Copy"; }, 1200);
    }

    window.__prismCopyDone = function (ok) {
        if (pendingCopyBtn && ok) flashCopied(pendingCopyBtn);
        pendingCopyBtn = null;
    };

    function doCopy(btn) {
        var codeEl = btn.parentNode.querySelector(".prism-math__code");
        var text = codeEl ? codeEl.textContent : "";
        if (window.__prismCopy) {
            pendingCopyBtn = btn;
            try { window.__prismCopy(text); } catch (e) { pendingCopyBtn = null; }
        } else {
            // No host bridge (test harness): optimistic flash.
            flashCopied(btn);
        }
    }

    document.addEventListener("click", function (e) {
        // Copy is bound directly on host-created nodes in buildWidget (review #2); the
        // document handler only owns reveal/collapse, which is a harmless CSS toggle even
        // if a class were forged.
        var w = e.target.closest && e.target.closest(".prism-math");
        if (w) {
            var wasOpen = w.classList.contains("is-open");
            collapseAll();
            if (!wasOpen) w.classList.add("is-open");
            e.stopPropagation();
            return;
        }
        collapseAll();
    });
    document.addEventListener("keydown", function (e) {
        if (e.key === "Escape") { collapseAll(); return; }
        if (e.key !== "Enter" && e.key !== " ") return;
        var w = document.activeElement && document.activeElement.closest
            ? document.activeElement.closest(".prism-math") : null;
        if (w) {
            var wasOpen = w.classList.contains("is-open");
            collapseAll();
            if (!wasOpen) w.classList.add("is-open");
            e.preventDefault();
        }
    });

    // Exposed for browser-level tests.
    window.__prismRenderMarkdown = renderMarkdownToClean;
    window.__prismRenderInto = renderMarkdownInto;
    window.__prismHardenNode = hardenNode;
    window.__prismReady = true;
})();
