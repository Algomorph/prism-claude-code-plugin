/* Group 3 browser render assertions. Rendered content is built with the shipped
 * shell.js entry points, then a JSON summary is posted back via window.__prismTestResult.
 * Standard JS escaping applies here (unlike a Kotlin string literal). */
(function () {
    function box() { var d = document.createElement('div'); document.body.appendChild(d); return d; }

    var blockSource = '$$\\int_0^1 x^2\\,dx = \\frac{1}{3}$$';

    var blockBox = box();
    window.__prismRenderInto(blockBox, blockSource);

    var inlineBox = box();
    window.__prismRenderInto(inlineBox, 'value is $x^2$ inline');

    var codeBox = box();
    window.__prismRenderInto(codeBox, '```bash\nit cost $5 and $10\n```');

    var imgBox = box();
    var png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
    var okImg = document.createElement('img');
    okImg.src = png;
    okImg.className = 'prism-image';
    imgBox.appendChild(okImg);

    // A remote markdown image must be replaced with a blocked marker.
    var remoteBox = box();
    window.__prismRenderInto(remoteBox, '![remote](https://evil.example/x.png)');

    var byteExact = false;
    var code = blockBox.querySelector('.prism-math__code');
    if (code) { byteExact = (code.textContent === blockSource); }

    var res = {
        blockMathWidgets: blockBox.querySelectorAll('.prism-math--block').length,
        inlineMathWidgets: inlineBox.querySelectorAll('.prism-math--inline').length,
        katexRendered: document.querySelectorAll('.prism-math__render .katex').length,
        mathInCode: codeBox.querySelectorAll('.prism-math').length,
        byteExact: byteExact,
        imagesRendered: imgBox.querySelectorAll('img[src^="data:image/"]').length,
        blockedImages: remoteBox.querySelectorAll('.prism-image--blocked').length
    };
    window.__prismTestResult(JSON.stringify(res));
})();
