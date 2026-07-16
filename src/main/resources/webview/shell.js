/* Prism hybrid chat-shell — browser-side runtime.
 *
 * This is the ONLY code that turns transcript content into DOM. It runs inside
 * the JCEF page. Kotlin never emits HTML (design §6.1/§6.2, R18): it sends a
 * base64-encoded TranscriptDelta (typed blocks + pre-resolved media, never
 * markup), and this file renders + sanitizes it here.
 *
 * Security pipeline, per block (design §6.8):
 *   1. escape raw '<' in the markdown source  -> marked cannot emit passthrough
 *      HTML tags (blockquote '>' and entities '&' are preserved so real
 *      markdown still works).
 *   2. marked.parse                            -> HTML from the now-inert source.
 *   3. DOMPurify.sanitize (pinned, allowlist)  -> strip handlers/script/iframe/…
 *   4. insert trusted nodes (images already resolved to data: URIs by the Kotlin
 *      MediaResolver; KaTeX math widgets are added in Group 3) AFTER sanitize,
 *      so hostile text can never forge one.
 *
 * The host injects two globals before this script's IIFE runs:
 *   window.__prismAck(payloadJson)  — post an ack to Kotlin (JBCefJSQuery).
 *   window.__prismLink(href)        — route a link click to the OS browser.
 * Both are optional (absent in a plain browser harness); calls are guarded.
 */
(function () {
    "use strict";

    var DP = window.DOMPurify;

    // Sanitizer allowlist — formatting, code, links, images, and the KaTeX span
    // classes Group 3 will populate. No script/style/iframe/object/svg, no event
    // handlers. data: URIs are permitted only for <img> (CSP also enforces this).
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
        ALLOW_DATA_ATTR: false,
        // data: only ever appears on <img>, whose src Kotlin already produced.
        ADD_URI_SAFE_ATTR: [],
        RETURN_TRUSTED_TYPE: false
    };

    // Link schemes we let through to the OS browser. Everything else is inert.
    var SAFE_LINK_SCHEMES = /^(https?):/i;

    function escapeTagOpen(src) {
        // Kill HTML tags at the source without breaking blockquotes ('>') or
        // entities ('&'): only '<' can start a tag.
        return String(src).replace(/</g, "&lt;");
    }

    function decodeB64Utf8(b64) {
        var bin = atob(b64);
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new TextDecoder("utf-8").decode(bytes);
    }

    // Render a markdown string to a sanitized DocumentFragment. Exposed on
    // window for browser-level tests to assert against directly.
    function renderMarkdownToClean(md) {
        var inert = escapeTagOpen(md == null ? "" : md);
        var html = window.marked ? window.marked.parse(inert, { gfm: true, breaks: false }) : inert;
        return DP.sanitize(html, SANITIZE_CONFIG);
    }

    // After sanitize, harden links (route http(s) to the OS browser; neutralize
    // anything else) and images (only data: survives; others become a marker).
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
                    // javascript:, file:, vbscript:, data:, mailto:, …  — inert.
                    a.removeAttribute("href");
                    a.classList.add("prism-link--blocked");
                }
            })(links[i]);
        }
        var imgs = root.querySelectorAll ? root.querySelectorAll("img") : [];
        for (var j = 0; j < imgs.length; j++) {
            var src = imgs[j].getAttribute("src") || "";
            if (src.indexOf("data:image/") !== 0) {
                // Any non-data image (remote, file:, unresolved) is replaced with a
                // neutral marker — never left as a live element that could fetch.
                var marker = document.createElement("span");
                marker.className = "prism-image--blocked";
                marker.textContent = imgs[j].getAttribute("alt") || "[blocked image]";
                imgs[j].parentNode.replaceChild(marker, imgs[j]);
            }
        }
        return root;
    }

    // Build the DOM node for one typed block payload (design §6.2/§6.3). The
    // math/byte-exact-copy widgets and richer tool chrome arrive in Group 3;
    // here we render text/markdown safely and stub the other kinds inertly.
    function renderBlock(block) {
        var el = document.createElement("div");
        el.className = "prism-block prism-block--" + (block.kind || "unknown") +
            " prism-vis--" + (block.visibility || "visible");
        switch (block.kind) {
            case "text":
            case "thinking": {
                var body = document.createElement("div");
                body.className = "prism-block__body";
                body.innerHTML = renderMarkdownToClean(block.markdown || "");
                hardenNode(body);
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
                    inp.textContent = block.toolInput; // textContent: never parsed as HTML
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
                    img.src = block.imageDataUri;       // trusted: Kotlin MediaResolver produced it
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
            default: {
                // toolReference / unknown / anything future — a small neutral marker.
                var u = document.createElement("span");
                u.className = "prism-unsupported";
                u.textContent = block.label || "[unsupported content]";
                el.appendChild(u);
            }
        }
        return el;
    }

    function renderMessage(id, payload) {
        var msg = document.createElement("div");
        msg.className = "prism-msg prism-msg--" + (payload.role || "assistant");
        msg.setAttribute("data-prism-id", id);
        var role = document.createElement("div");
        role.className = "prism-msg__role";
        role.textContent = payload.roleLabel || payload.role || "";
        msg.appendChild(role);
        var blocks = payload.blocks || [];
        for (var i = 0; i < blocks.length; i++) {
            if (blocks[i].visibility === "hidden-internal") continue; // preserved in model, not shown
            msg.appendChild(renderBlock(blocks[i]));
        }
        return msg;
    }

    // --- delta application -----------------------------------------------------

    var state = { epoch: -1, revision: -1 };

    function contentEl() { return document.getElementById("prism-content"); }

    function applyOperation(op) {
        var root = contentEl();
        if (op.op === "reset") {
            root.innerHTML = "";
            return;
        }
        if (op.op === "remove") {
            var gone = root.querySelector('[data-prism-id="' + cssEscape(op.id) + '"]');
            if (gone) gone.parentNode.removeChild(gone);
            return;
        }
        if (op.op === "upsert") {
            var node = renderMessage(op.id, op.payload || {});
            var existing = root.querySelector('[data-prism-id="' + cssEscape(op.id) + '"]');
            if (existing) {
                existing.parentNode.replaceChild(node, existing);
            } else {
                root.appendChild(node);
            }
        }
    }

    function cssEscape(s) {
        return String(s).replace(/["\\]/g, "\\$&");
    }

    function stickyBottom(before) {
        // Keep pinned to bottom unless the user scrolled up.
        var doc = document.documentElement;
        var atBottom = (doc.scrollHeight - doc.scrollTop - doc.clientHeight) < 40;
        return atBottom;
    }

    // Entry point the host calls: base64(JSON(TranscriptDelta)). Nothing but
    // base64 is ever interpolated into the executeJavaScript string (R15).
    window.__prismApplyDelta = function (b64) {
        var status = "ok";
        var epoch = state.epoch, revision = state.revision;
        try {
            var delta = JSON.parse(decodeB64Utf8(b64));
            epoch = delta.epoch;
            revision = delta.revision;
            // A reset/epoch change wipes prior content.
            if (delta.epoch !== state.epoch) {
                contentEl().innerHTML = "";
            }
            var pin = stickyBottom();
            var ops = delta.operations || [];
            for (var i = 0; i < ops.length; i++) applyOperation(ops[i]);
            state.epoch = delta.epoch;
            state.revision = delta.revision;
            if (pin) window.scrollTo(0, document.documentElement.scrollHeight);
        } catch (e) {
            status = "error:" + (e && e.message ? e.message : "unknown");
        }
        // Ack AFTER layout: rAF guarantees the browser has laid out inserted
        // nodes (and any images/KaTeX) before we report the revision rendered.
        var ack = function () {
            if (window.__prismAck) {
                try { window.__prismAck(JSON.stringify({ epoch: epoch, revision: revision, status: status })); }
                catch (x) {}
            }
        };
        if (window.requestAnimationFrame) {
            window.requestAnimationFrame(function () { window.requestAnimationFrame(ack); });
        } else {
            ack();
        }
        return status;
    };

    // Expose the pure renderer for browser-level tests.
    window.__prismRenderMarkdown = renderMarkdownToClean;
    window.__prismHardenNode = hardenNode;
    window.__prismReady = true;
})();
