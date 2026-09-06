package com.shatyuka.zhiliao.hooks;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.ValueCallback;
import android.widget.Toast;

import com.shatyuka.zhiliao.Helper;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class CopyUnlock implements IHook {
    private static final int COPY_SELECTION = 0x6f7a0101;
    private static final int COPY_ALL = 0x6f7a0102;
    private static final int SELECT_ALL = 0x6f7a0103;
    private String script;
    private ClassLoader classLoader;
    private Class<?> x5WebView;

    @Override
    public String getName() {
        return "自由复制回答和文章";
    }

    @Override
    public void init(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void hook() throws Throwable {
        if (!Helper.prefs.getBoolean("switch_mainswitch", false)
                || !Helper.prefs.getBoolean("switch_copy_unlock", false)) return;

        try (InputStream input = Helper.modRes.getAssets().open("copy_unlock.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            script = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }

        // Both answer AppViews and ArticleFragment use the Mercury WebView wrapper.
        hookPageCallbacks(Helper.WebViewClientWrapper, android.webkit.WebView.class);
        try {
            x5WebView = classLoader.loadClass("com.tencent.smtt.sdk.WebView");
            Class<?> wrapper = classLoader.loadClass("com.zhihu.android.app.mercury.web.x5.f");
            hookPageCallbacks(wrapper, x5WebView);
        } catch (ClassNotFoundException ignored) {
            // X5 is optional; the system WebView hooks remain available.
        } catch (Throwable error) {
            XposedBridge.log("[Zhiliao] CopyUnlock X5 page callbacks: " + error);
        }

        // Wrap the final callback after Mercury has installed its own menu filters.
        for (Method method : View.class.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("startActionMode") && types.length > 0
                    && types[0] == ActionMode.Callback.class) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean webView = param.thisObject instanceof android.webkit.WebView
                                || (x5WebView != null && x5WebView.isInstance(param.thisObject));
                        if (!webView || !isReadingView(param.thisObject)) return;
                        if (isEditing(param.thisObject)) return;
                        ActionMode.Callback callback = (ActionMode.Callback) param.args[0];
                        if (callback == null || callback instanceof CopyCallback) return;
                        install(param.thisObject);
                        param.args[0] = new CopyCallback(param.thisObject, callback);
                    }
                });
            }
        }
        hookX5Selection();
    }

    private void hookPageCallbacks(Class<?> wrapper, Class<?> webView) {
        for (Method method : wrapper.getMethods()) {
            String name = method.getName();
            Class<?>[] types = method.getParameterTypes();
            if ((name.equals("onPageStarted") || name.equals("onPageFinished")
                    || name.equals("onPageCommitVisible")) && types.length >= 2
                    && types[0] == webView) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        install(param.args[0]);
                    }
                });
            }
        }
    }

    private boolean isReadingView(Object webView) {
        try {
            String url = (String) webView.getClass().getMethod("getUrl").invoke(webView);
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            String scheme = uri.getScheme();
            if (path == null) return false;
            boolean contentPath = path.matches("(?i).*(?:/answer(?:/|$)|/article(?:/|$)|/column(?:/|$)|/p/).*");
            boolean zhihu = host != null && (host.equalsIgnoreCase("zhihu.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".zhihu.com"));
            return contentPath && ((zhihu && ("https".equals(scheme) || "http".equals(scheme)))
                    || "file".equals(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void install(Object webView) {
        if (!isReadingView(webView)) return;
        ((View) webView).setLongClickable(true);
        evaluate(webView, "install", null);
    }

    private boolean isEditing(Object webView) {
        try {
            Object hit = webView.getClass().getMethod("getHitTestResult").invoke(webView);
            return hit != null && ((Integer) hit.getClass().getMethod("getType").invoke(hit))
                    == android.webkit.WebView.HitTestResult.EDIT_TEXT_TYPE;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void evaluate(Object webView, String action, ValueCallback<String> callback) {
        String js = script.replace("__ZHILIAO_COPY_ACTION__", JSONObject.quote(action));
        try {
            if (webView instanceof android.webkit.WebView) {
                ((android.webkit.WebView) webView).evaluateJavascript(js, callback);
                return;
            }
            // X5 uses its own ValueCallback interface; do not depend on the TBS SDK.
            Class<?> valueCallback = classLoader.loadClass("com.tencent.smtt.sdk.ValueCallback");
            Object proxy = callback == null ? null : Proxy.newProxyInstance(classLoader,
                    new Class<?>[]{valueCallback}, (object, method, args) -> {
                        if (method.getName().equals("onReceiveValue")) {
                            callback.onReceiveValue((String) args[0]);
                        } else if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(object);
                        } else if (method.getName().equals("equals")) {
                            return object == args[0];
                        } else if (method.getName().equals("toString")) {
                            return "ZhiliaoCopyCallback";
                        }
                        return null;
                    });
            webView.getClass().getMethod("evaluateJavascript", String.class, valueCallback)
                    .invoke(webView, js, proxy);
        } catch (Exception error) {
            XposedBridge.log("[Zhiliao] CopyUnlock: " + error);
            if (callback != null) callback.onReceiveValue(null);
        }
    }

    private void copy(Object webView, ActionMode mode, boolean all) {
        evaluate(webView, all ? "all" : "selection", value -> {
            try {
                Object decoded = value == null ? null : new JSONTokener(value).nextValue();
                if (!(decoded instanceof String) || ((String) decoded).isEmpty()) {
                    Helper.toast(all ? "未找到已加载的正文" : "请先选中正文", Toast.LENGTH_SHORT);
                    return;
                }
                Context context = ((View) webView).getContext();
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                clipboard.setPrimaryClip(ClipData.newPlainText("", (String) decoded));
                if (mode != null) mode.finish();
                Helper.toast(all ? "已复制全文" : "已复制", Toast.LENGTH_SHORT);
            } catch (Exception error) {
                XposedBridge.log("[Zhiliao] CopyUnlock: " + error);
                Helper.toast("复制失败，请重新选择正文", Toast.LENGTH_SHORT);
            }
        });
    }

    private boolean isCopyItem(MenuItem item) {
        String title = String.valueOf(item.getTitle());
        return item.getItemId() == android.R.id.copy || item.getItemId() == COPY_SELECTION
                || title.equals("复制") || title.equalsIgnoreCase("copy");
    }

    private boolean isSelectAllItem(MenuItem item) {
        String title = String.valueOf(item.getTitle());
        return item.getItemId() == android.R.id.selectAll || item.getItemId() == SELECT_ALL
                || title.equals("全选") || title.equalsIgnoreCase("select all");
    }

    private boolean handleItem(Object webView, ActionMode mode, MenuItem item) {
        if (item.getItemId() == COPY_ALL || isCopyItem(item)) {
            copy(webView, mode, item.getItemId() == COPY_ALL);
            return true;
        }
        if (isSelectAllItem(item)) {
            evaluate(webView, "selectAll", value -> {
                if ("true".equals(value)) {
                    if (mode != null) mode.invalidate();
                } else {
                    Helper.toast("未找到已加载的正文", Toast.LENGTH_SHORT);
                }
            });
            return true;
        }
        return false;
    }

    private void prepareMenu(Object webView, ActionMode mode, Menu menu) {
        boolean hasCopy = false;
        boolean hasSelectAll = false;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            hasCopy |= isCopyItem(item);
            hasSelectAll |= isSelectAllItem(item);
            if (isCopyItem(item) || isSelectAllItem(item) || item.getItemId() == COPY_ALL) {
                item.setVisible(true).setEnabled(true);
                item.setOnMenuItemClickListener(clicked -> handleItem(webView, mode, clicked));
            }
        }
        if (!hasCopy) addItem(webView, mode, menu, COPY_SELECTION, "复制");
        if (!hasSelectAll) addItem(webView, mode, menu, SELECT_ALL, "全选正文");
        if (menu.findItem(COPY_ALL) == null) addItem(webView, mode, menu, COPY_ALL, "复制全文");
    }

    private void addItem(Object webView, ActionMode mode, Menu menu, int id, String title) {
        menu.add(Menu.NONE, id, Menu.NONE, title)
                .setOnMenuItemClickListener(item -> handleItem(webView, mode, item))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
    }

    private class CopyCallback extends ActionMode.Callback2 {
        private final Object webView;
        private final ActionMode.Callback delegate;

        CopyCallback(Object webView, ActionMode.Callback delegate) {
            this.webView = webView;
            this.delegate = delegate;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            delegate.onCreateActionMode(mode, menu);
            prepareMenu(webView, mode, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            delegate.onPrepareActionMode(mode, menu);
            prepareMenu(webView, mode, menu);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return handleItem(webView, mode, item) || delegate.onActionItemClicked(mode, item);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            delegate.onDestroyActionMode(mode);
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (delegate instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) delegate).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }

    private void hookX5Selection() {
        try {
            // Local smali: X5SelectionInterfaceImp.java, field d is the X5WebView.
            Class<?> selection = classLoader.loadClass("com.zhihu.android.app.mercury.web.x5.c");
            Field webView = selection.getDeclaredField("d");
            if (!View.class.isAssignableFrom(webView.getType())
                    || !webView.getType().getName().endsWith("X5WebView")) return;
            webView.setAccessible(true);
            Method prepare = selection.getDeclaredMethod("onPrepareActionMode", ActionMode.class, Menu.class);
            XposedBridge.hookMethod(prepare, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object view = webView.get(param.thisObject);
                    if (!isReadingView(view) || isEditing(view)) return;
                    install(view);
                    prepareMenu(view, (ActionMode) param.args[0], (Menu) param.args[1]);
                    param.setResult(true);
                }
            });
            Method click = selection.getDeclaredMethod("onActionItemClicked", ActionMode.class, MenuItem.class);
            XposedBridge.hookMethod(click, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object view = webView.get(param.thisObject);
                    if (isReadingView(view) && !isEditing(view) && handleItem(view, (ActionMode) param.args[0],
                            (MenuItem) param.args[1])) param.setResult(true);
                }
            });
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException ignored) {
            // The optional X5 implementation is absent or renamed on some versions.
        } catch (Throwable error) {
            XposedBridge.log("[Zhiliao] CopyUnlock X5: " + error);
        }
    }
}
