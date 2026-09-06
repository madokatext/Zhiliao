(function (action) {
    'use strict';
    var api = window.__zhiliaoCopyUnlock;
    if (!api) {
        var selectors = '.AnswerItem .RichContent-inner,.Post-RichText,.Answer-content,' +
            '.Article-content,[itemprop="articleBody"],.RichText,.ztext';
        var excluded = '.CommentItem,.CommentList,.Comments-container,.CommentsV2,' +
            '.Recommendations,[contenteditable="true"],textarea,input';
        var styleId = 'zhiliao-copy-unlock-style';

        function roots() {
            return Array.prototype.filter.call(document.querySelectorAll(selectors), function (node) {
                return !node.closest(excluded) && node.getClientRects().length > 0;
            });
        }

        function selectedRoot() {
            var selection = window.getSelection();
            var node = selection && selection.anchorNode;
            var candidates = roots();
            var root = null;
            candidates.forEach(function (candidate) {
                if (node && candidate.contains(node) && (!root || candidate.contains(root))) {
                    root = candidate;
                }
            });
            return root;
        }

        function contentRoot() {
            var root = selectedRoot();
            if (root) return root;
            var length = 0;
            roots().forEach(function (candidate) {
                var size = (candidate.innerText || '').length;
                if (size > length) {
                    root = candidate;
                    length = size;
                }
            });
            return root;
        }

        function enableSelection() {
            var host = document.head || document.documentElement;
            if (!host) return;
            if (!document.getElementById(styleId)) {
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = selectors.split(',').map(function (selector) {
                    return selector + ',' + selector + ' *';
                }).join(',') + '{-webkit-user-select:text!important;user-select:text!important;' +
                    '-webkit-touch-callout:default!important}';
                host.appendChild(style);
            }
            // Inline !important rules outrank the stylesheet on some page versions.
            roots().forEach(function (root) {
                var nodes = [root].concat(Array.prototype.slice.call(root.querySelectorAll('[style]')));
                nodes.forEach(function (node) {
                    ['user-select', '-webkit-user-select'].forEach(function (property) {
                        var value = node.style.getPropertyValue(property);
                        if (value && value !== 'text') node.style.setProperty(property, 'text', 'important');
                    });
                });
            });
        }

        // Keep ordinary browser copy gestures usable as well as the native menu.
        window.addEventListener('copy', function (event) {
            if (!selectedRoot() || !event.clipboardData) return;
            var text = window.getSelection().toString();
            if (!text) return;
            event.clipboardData.setData('text/plain', text);
            event.preventDefault();
            event.stopImmediatePropagation();
        }, true);
        ['selectstart', 'contextmenu'].forEach(function (type) {
            window.addEventListener(type, function (event) {
                var node = event.target;
                if (node && node.nodeType !== 1) node = node.parentElement;
                if (node && node.closest(selectors) && !node.closest(excluded)) {
                    event.stopImmediatePropagation();
                }
            }, true);
        });
        document.addEventListener('DOMContentLoaded', enableSelection);
        // Answer pre-rendering and article navigation may replace the content later.
        var scheduled = false;
        var observer = new MutationObserver(function () {
            if (scheduled) return;
            scheduled = true;
            window.requestAnimationFrame(function () {
                scheduled = false;
                enableSelection();
            });
        });
        observer.observe(document, { childList: true, subtree: true, attributes: true,
            attributeFilter: ['style', 'class'] });
        api = window.__zhiliaoCopyUnlock = {
            enable: enableSelection,
            root: contentRoot,
            selectedRoot: selectedRoot
        };
    }
    api.enable();
    if (action === 'install') return null;
    if (action === 'selection') {
        return window.getSelection().toString();
    }
    var root = api.root();
    if (!root) return null;
    if (action === 'all') return root.innerText || root.textContent || null;
    if (action === 'selectAll') {
        var range = document.createRange();
        range.selectNodeContents(root);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        return true;
    }
    return null;
})(__ZHILIAO_COPY_ACTION__)
