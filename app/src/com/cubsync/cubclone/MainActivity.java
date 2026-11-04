package com.cubsync.cubclone;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 应用分身主界面 —— 结构照 myui 手机管家的应用分身页：
 * 每个应用一张卡片，第一行是主身份那份 + 「创建分身」按钮，
 * 下面每行是一个分身（图标带角标、名字带序号、右侧改名/删除）。
 */
public class MainActivity extends Activity {

    private static final String JAR = "cubclone.jar";
    private static final String REMOTE = "/data/local/tmp/cubclone.jar";
    private static final String WB = "CLASSPATH=" + REMOTE + " app_process /system/bin CubClone";
    private static final int REQ_SHIZUKU = 1001;
    private static final Pattern ROW =
            Pattern.compile("CLONE\\s+UserInfo\\{(\\d+):([^:]*):");

    /** 分身角标配色，按序号轮换 */
    private static final int[] BADGE = {
            0xFF34A853, 0xFFEA8600, 0xFF9334E6, 0xFF1A73E8, 0xFFE52592
    };

    static final class Clone {
        int uid;
        String profile;
        /** 这个空间里该应用被我们隐藏了（pm disable-user）：仍列出来，灰显，长按可启用 */
        boolean off;
        /** 别人建的分身空间（一加 MultiApp 之类）：只显示，不清理、不给删、不给钉图标 */
        boolean foreign;
    }

    static final class Group {
        String pkg;
        String label;
        Drawable icon;
        final List<Clone> clones = new ArrayList<Clone>();
    }

    /** 网格平铺项：一个分身或一个主应用 */
    static final class FlatItem {
        final Group g;
        final Clone c;   // null = 主应用
        final int idx;   // clone 在 g.clones 里的序号；主应用 = -1
        FlatItem(Group g, Clone c, int idx) { this.g = g; this.c = c; this.idx = idx; }
    }

    private LinearLayout listBox;
    private View emptyView;
    private TextView status;
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView title, hint;
    private android.widget.ImageButton btnTheme, btnSettings, btnRefresh, btnScope, btnSearch, btnGrid;
    /** 列表容器，用来判断是否已经滚到顶（下拉才出搜索） */
    private android.widget.ScrollView listWrap;
    /** 启动器式网格（自绘：ScrollView + 行布局） */
    private android.widget.ScrollView gridWrap;
    private LinearLayout gridBox;
    /** 网格格子高度：布局就绪后统一算 */
    private int gridItemH;
    /** 「最近打开」区（动态创建，挂进列表/网格滚动内容顶部，随滚动收起） */
    private LinearLayout recentHeader;
    private View recentDivider;
    private TextView recentTitle;
    private LinearLayout recentRow;
    private View searchRow;
    /** 所有有效分身（不分用户/系统页签）：key="uid:pkg"，供「最近打开」反查图标/名字/禁用态 */
    private final Map<String, Clone> allClones = new HashMap<String, Clone>();

    private final List<Group> groups = new ArrayList<Group>();

    /** 主界面多选：长按应用进入多选，勾选多个应用批量创建分身 */
    private boolean multi = false;
    private final Set<String> selected = new HashSet<String>();
    private View multiBar;
    private TextView multiCount;
    private android.widget.Button btnSelectAll, btnInvert, btnAdd;

    private final Map<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private final Map<String, String> labelCache = new HashMap<String, String>();
    /** 只认第三方应用：分身用户里天然带着一堆系统应用 */
    private final Set<String> userApps = new HashSet<String>();
    /** 三方包集合的副本：设置页要按它数「这个空间里有几个三方应用」（不额外起 shell） */
    static volatile Set<String> THIRD = new HashSet<String>();
    private final Object metaLock = new Object();

    private EditText search;
    private android.widget.Switch scopeSwitch;

    /** 0 = 用户应用，1 = 系统应用 */
    static int tab = 0;
    private TextView tabUserText, tabSysText;
    private View tabUserLine, tabSysLine;
    /** 系统应用单独放一档，默认不掺进用户列表 */
    private final Set<String> sysApps = new HashSet<String>();

    /** 显示范围开关：主界面和设置页共用同一个 show_all */
    private final android.widget.CompoundButton.OnCheckedChangeListener scopeListener =
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton v, boolean on) {
                    sp().edit().putBoolean("show_all", on).apply();
                    render();
                }
            };
    private String kw = "";

    /** 记下进页面时的语言，从设置页回来发现变了就重建（XML 文本才会换） */
    private String langAtCreate;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        attach(this);
        langAtCreate = Locales.tag(this);
        setContentView(R.layout.activity_main);

        listBox = findViewById(R.id.clone_app_list);
        emptyView = findViewById(R.id.not_find_app);
        status = findViewById(R.id.status);

        search = findViewById(R.id.search);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(android.text.Editable e) {
                kw = e.toString().trim().toLowerCase();
                render();
            }
        });

        // 「显示全部」已改成顶栏漏斗图标，布局里那颗开关删掉了，这里不再绑控件

        tab = sp().getInt("tab", 0);
        tabUserText = findViewById(R.id.tab_user_text);
        tabSysText = findViewById(R.id.tab_sys_text);
        tabUserLine = findViewById(R.id.tab_user_line);
        tabSysLine = findViewById(R.id.tab_sys_line);
        findViewById(R.id.tab_user).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchTab(0); }
        });
        findViewById(R.id.tab_sys).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchTab(1); }
        });

        initHeader();

        Shizuku.addRequestPermissionResultListener(onShizukuResult);

        new Thread(new Runnable() {
            public void run() {
                Shell.checkRoot();
                loadAppMeta();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (Shell.rootOk) startWork();
                        else {
                            say(getString(R.string.st_no_root));
                            initShizuku();
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String langNow = Locales.tag(this);
        if (langAtCreate == null ? langNow != null : !langAtCreate.equals(langNow)) {
            recreate();
            return;
        }
        applyChrome();      // 设置页可能改了颜色模式 / 显示策略
        if (scopeSwitch != null) {
            boolean v = sp().getBoolean("show_all", false);
            if (scopeSwitch.isChecked() != v) {
                scopeSwitch.setOnCheckedChangeListener(null);
                scopeSwitch.setChecked(v);
                scopeSwitch.setOnCheckedChangeListener(scopeListener);
            }
        }
        boolean dm = isNight(), bm2 = isBlack();
        if (dirty || groups.isEmpty()) {
            lastDark = dm;
            lastBlack = bm2;
            refresh();
        } else if (dm != lastDark || bm2 != lastBlack) {
            // 只有主题真的换了才重建列表。否则每次从别处回来都 render() 一遍，
            // 列表闪一下、滚动位置也丢（点「加桌面图标」回来就中招）
            lastDark = dm;
            lastBlack = bm2;
            render();
        }
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(onShizukuResult);
        super.onDestroy();
    }

    // ---------------- 顶栏：颜色模式 / 更多 ----------------

    private static final int THEME_AUTO = 0, THEME_DARK = 1, THEME_LIGHT = 2, THEME_BLACK = 3;

    private void initHeader() {
        title = findViewById(R.id.title);
        btnTheme = findViewById(R.id.btn_theme);
        btnSettings = findViewById(R.id.btn_settings);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnScope = findViewById(R.id.btn_scope);
        btnSearch = findViewById(R.id.btn_search);
        btnGrid = findViewById(R.id.btn_grid);
        listWrap = findViewById(R.id.list_wrap);
        gridWrap = findViewById(R.id.grid_wrap);
        gridBox = findViewById(R.id.grid_box);
        // 「最近打开」区：动态创建，挂在列表/网格滚动内容顶部（上滑随内容滚走），
        // 边距在挂载时按所在视图对齐（列表 16dp / 网格 8dp）
        createRecentHeader();
        // 惯例做法（Material 的 AppBarLayout / iOS 的 hidesSearchBarWhenScrolling）：搜索栏
        // 跟着列表滚出去，不是靠手势猜起手位置。之前要求「起手在列表上 40%」，列表滚过一段
        // 或手落在下半屏就收不起来，所以时灵时不灵。
        listWrap.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    private int last = 0;

                    public void onScrollChanged() {
                        int y = listWrap.getScrollY();
                        int dy = y - last;
                        last = y;
                        if (dy <= 0) return;            // 只认向上滚
                        collapseSearchIfIdle();
                    }
                });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSearch(); }
        });
        btnGrid.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setGridMode(!gridMode());
                if (gridMode()) {
                    multi = false;
                    selected.clear();
                    multiBar.setVisibility(View.GONE);
                }
                render();
            }
        });
        searchRow = findViewById(R.id.search_row);

        btnScope.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleScope(); }
        });

        btnTheme.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { cycleTheme(); }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refreshNow(); }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        multiBar = findViewById(R.id.multi_bar);
        multiCount = findViewById(R.id.multi_count);
        btnSelectAll = findViewById(R.id.btn_select_all);
        btnInvert = findViewById(R.id.btn_invert);
        btnAdd = findViewById(R.id.btn_add);
        btnSelectAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selected.clear();
                for (Group g : groups) selected.add(g.pkg);
                render();
            }
        });
        btnInvert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Set<String> inv = new HashSet<String>();
                for (Group g : groups) if (!selected.contains(g.pkg)) inv.add(g.pkg);
                selected.clear();
                selected.addAll(inv);
                render();
            }
        });
        btnAdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (selected.isEmpty()) {
                    toast(getString(R.string.batch_none_selected));
                    return;
                }
                showSpacePicker();
            }
        });

        applyChrome();
    }

    /** 顶栏、文字、图标颜色全部手动设，不依赖系统主题，切模式立刻生效 */
    private void applyChrome() {
        boolean dark = isNight();
        boolean black = isBlack();
        int bg = black ? 0xFF000000 : (dark ? 0xFF121317 : 0xFFFDFDFE);
        int fg = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        int sec = black ? 0xFF8A8F96 : (dark ? 0xFF9AA0A6 : 0xFF5F6368);

        findViewById(R.id.root).setBackgroundColor(bg);
        findViewById(R.id.header).setBackgroundColor(bg);
        title.setTextColor(fg);
        if (status != null) status.setTextColor(sec);
        if (search != null) {
            search.setTextColor(fg);
            search.setHintTextColor(sec);
        }
        if (btnSearch != null) btnSearch.setColorFilter(fg);
        if (btnScope != null) {
            // 漏斗高亮＝正在过滤（只看开过分身的）；显示全部时不亮
            boolean filtering = !sp().getBoolean("show_all", false);
            btnScope.setColorFilter(filtering ? (dark ? 0xFFA8C7FA : 0xFF0B57D0) : fg);
        }
        if (btnRefresh != null) btnRefresh.setColorFilter(fg);
        if (btnSettings != null) btnSettings.setColorFilter(fg);
        if (btnGrid != null) btnGrid.setColorFilter(fg);
        updateGridBtn();

        if (btnTheme != null) {
            int t = sp().getInt("theme", THEME_AUTO);
            btnTheme.setImageResource(t == THEME_DARK ? R.drawable.ic_theme_dark
                    : t == THEME_LIGHT ? R.drawable.ic_theme_light
                    : R.drawable.ic_theme_auto);
            btnTheme.setColorFilter(fg);
        }
        try {
            getWindow().setStatusBarColor(bg);
        } catch (Throwable ignored) {
        }
        applyTabs();
    }

    /** 行按下时给点反馈：用 foreground 盖一层水波纹，不动卡片自己的圆角背景 */
    static void ripple(View v) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && v.getContext().getTheme().resolveAttribute(
                            android.R.attr.selectableItemBackground, tv, true)
                    && tv.resourceId != 0) {
                v.setForeground(v.getContext().getDrawable(tv.resourceId));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 搜索框开着的时候，返回先收起搜索，不要直接退到桌面 */
    @Override
    public void onBackPressed() {
        if (searchRow != null && searchRow.getVisibility() == View.VISIBLE) {
            toggleSearch();
            return;
        }
        // 多选（批量创建）时返回先退出多选，回到普通列表，别直接退 app
        if (multi) {
            multi = false;
            selected.clear();
            multiBar.setVisibility(View.GONE);
            render();
            return;
        }
        super.onBackPressed();
    }

    private boolean canScrollUp() {
        return listWrap != null && listWrap.getScrollY() > 0;
    }

    /**
     * 搜索框展开、但还没输入关键字时，列表一上滑就收起（连键盘一起收）。
     * 有输入就不收——iOS 的 hidesSearchBarWhenScrolling 在搜索激活时同样不动，
     * 免得正在找东西的人一滑就丢了上下文。收起时不 render：kw 本来就是空的，列表没变化。
     */
    private void collapseSearchIfIdle() {
        if (searchRow == null || searchRow.getVisibility() != View.VISIBLE) return;
        if (kw != null && kw.length() > 0) return;
        searchRow.setVisibility(View.GONE);
        hideKeyboard();
    }

    /** 下拉出搜索框（弹键盘），上推收起并清掉关键字 */
    private void toggleSearch() {
        if (searchRow == null) return;
        if (searchRow.getVisibility() == View.VISIBLE) {
            searchRow.setVisibility(View.GONE);
            search.setText("");
            kw = "";
            hideKeyboard();
            render();
            return;
        }
        searchRow.setVisibility(View.VISIBLE);
        search.setFocusableInTouchMode(true);
        search.requestFocus();
        // 刚把控件从 GONE 变 VISIBLE 时窗口还没量完，立刻 showSoftInput 会被丢掉
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        search.postDelayed(new Runnable() {
            public void run() {
                android.view.inputmethod.InputMethodManager im =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(search,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null && search != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        if (search != null) search.clearFocus();
    }

    /** 漏斗图标：切换「只看开过分身的 / 显示全部可分身应用」 */
    private void toggleScope() {
        boolean on = !sp().getBoolean("show_all", false);
        sp().edit().putBoolean("show_all", on).apply();
        if (scopeSwitch != null) {
            scopeSwitch.setOnCheckedChangeListener(null);
            scopeSwitch.setChecked(on);
            scopeSwitch.setOnCheckedChangeListener(scopeListener);
        }
        applyChrome();
        render();
    }

    /** 左右滑动切页签：在 Activity 这层收事件，比挂 ScrollView 的 OnTouchListener 靠谱 */
    private float swipeX0, swipeY0;
    private long swipeT0;
    private boolean swipeAtTop;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            swipeX0 = ev.getX();
            swipeY0 = ev.getY();
            swipeT0 = System.currentTimeMillis();
        } else if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            long ms = System.currentTimeMillis() - swipeT0;
            float dx = ev.getX() - swipeX0, dy = ev.getY() - swipeY0;
            // 横划切页签：距离够远 + 基本水平 + 手速够快，才认（不然点按钮、斜着滚都会误触）
            if (Math.abs(dx) > dp(150) && Math.abs(dx) > Math.abs(dy) * 3f && ms < 500) {
                int want = dx < 0 ? 1 : 0;
                if (want != tab) {
                    switchTab(want);
                    return true;
                }
            }
            // 上推收起搜索框：只认「方向竖直 + 够远 + 够快」，不再看起手落在屏幕哪一截。
            // 之前那个 nearTop 条件（列表在顶 或 起手在上 40%）就是「有时能收有时不能」的根源。
            // 「下拉出搜索」已删：最近打开挂进列表顶后，滑到顶再下拉就误触开搜索+键盘，
            // 来回滑动列表反复跳动。开搜索走顶栏放大镜。
            if (searchRow != null && searchRow.getVisibility() == View.VISIBLE) {
                if (dy < -dp(90) && Math.abs(dy) > Math.abs(dx) * 2f && ms < 1000) {
                    toggleSearch();
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void applyTabs() {
        if (tabUserText == null) return;
        int accent = isNight() ? 0xFFA8C7FA : 0xFF0B57D0;
        int idle = isNight() ? 0xFF9AA0A6 : 0xFF5F6368;
        boolean u = tab == 0;
        tabUserText.setTextColor(u ? accent : idle);
        tabSysText.setTextColor(u ? idle : accent);
        tabUserText.setTypeface(null, u ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        tabSysText.setTypeface(null, u ? android.graphics.Typeface.NORMAL
                : android.graphics.Typeface.BOLD);
        tabUserLine.setBackgroundColor(u ? accent : 0x00000000);
        tabSysLine.setBackgroundColor(u ? 0x00000000 : accent);
    }

    /** 换页签：两边的应用集合不同，必须重读一遍 */
    private void switchTab(int which) {
        if (tab == which) return;
        tab = which;
        sp().edit().putInt("tab", which).apply();
        groups.clear();
        applyTabs();
        refresh();
    }

    /** 点一下换模式：跟随系统 → 深色 → 浅色 → 黑色，图标直接反映当前模式 */
    private void cycleTheme() {
        int next = (sp().getInt("theme", THEME_AUTO) + 1) % 3;
        sp().edit().putInt("theme", next).apply();
        applyChrome();
        render();
    }

    // ---------------- Shizuku ----------------

    private final Shizuku.OnRequestPermissionResultListener onShizukuResult =
            new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int code, int grantResult) {
                    if (code != REQ_SHIZUKU) return;
                    Shell.shizukuOk = grantResult == PackageManager.PERMISSION_GRANTED;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            say(getString(Shell.shizukuOk ? R.string.shizuku_granted : R.string.shizuku_denied));
                            if (Shell.shizukuOk) startWork();
                            else refresh();
                        }
                    });
                }
            };

    private void initShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                say(getString(R.string.shizuku_not_running));
                refresh();          // 没权限也要把应用列表画出来
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Shell.shizukuOk = true;
                say(getString(R.string.shizuku_granted));
                startWork();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                say(getString(R.string.shizuku_denied_before));
            } else {
                say(getString(R.string.shizuku_requesting));
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable t) {
            say(getString(R.string.shizuku_unavailable, String.valueOf(t)));
        }
    }

    /** 权限到手后：释放内核 → 拉列表 */
    private void startWork() {
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = deploy();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!ok) say(getString(R.string.deploy_failed));
                        else settle();
                        refresh();      // 权限就绪，正式拉一次
                    }
                });
            }
        }).start();
    }

    // ---------------- 应用元信息 ----------------

    /** onCreate 和 onResume 会各起一个线程，不加锁会互相 clear 掉对方的成果 */
    private void loadAppMeta() {
        synchronized (metaLock) {
            loadAppMetaLocked();
        }
    }

    private void loadAppMetaLocked() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        if (ris == null || ris.isEmpty()) return;     // 没取到就别把现有数据清空
        iconCache.clear();
        labelCache.clear();
        userApps.clear();
        THIRD = new HashSet<String>();
        sysApps.clear();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            iconCache.put(pkg, ri.loadIcon(pm));
            labelCache.put(pkg, String.valueOf(ri.loadLabel(pm)));
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                    userApps.add(pkg);
                    THIRD.add(pkg);
                } else {
                    sysApps.add(pkg);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 数据 ----------------

    /** 顶栏刷新按钮：不管缓存，强制重读一次 */
    private void refreshNow() {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say(getString(R.string.refreshing));
        dirty = true;
        metaDirty = true;   // 刷新按钮要连应用清单一起重读（装/卸应用、改显示策略都靠它）
        refresh();
    }

    /** 数据只在真正改过之后重读；平时进出页面直接用缓存，不再每次都起 shell */
    static volatile boolean dirty = true;
    /** 应用清单（图标/名称/第三方还是系统）需要重读 */
    static volatile boolean metaDirty = false;
    private volatile boolean refreshing;
    /** 上一次 render 用的深浅色，用来判断 onResume 要不要重建列表 */
    private boolean lastDark, lastBlack;
    /** 我们建的空间在系统里的用户名前缀：跨 ROM 唯一可靠的归属标志 */
    static final String TAG = "WB_";

    /** 归属判据要用 Context 读本地登记；两个入口 Activity 的 onCreate 都会调 attach() */
    static Context appCtx;

    static void attach(Context c) {
        if (appCtx == null && c != null) appCtx = c.getApplicationContext();
    }

    /**
     * 归属判据只认「跟着空间走」的标记，不认 uid。
     * ⚠uid 会被系统复用：删掉 15 号空间后，系统的分身或别的空间可能又拿到 15 ⇒
     * 以前按 uid 登记就会把别人的空间当成自己建的（ESP 上就这么误判过）。
     * 现在用两条：① 建空间时用户名带 WB_ 前缀（改不掉、不复用）；
     * ② 建完往该空间自己的 settings 里写 wb_clone=1（存的是这个用户的库，随空间一起删）。
     */
    private static final Set<Integer> MARKED = new HashSet<Integer>();
    private static boolean marksLoaded;

    static boolean marked(int uid) {
        synchronized (MARKED) {
            return MARKED.contains(Integer.valueOf(uid));
        }
    }

    static Set<Integer> markedUids() {
        synchronized (MARKED) {
            return new HashSet<Integer>(MARKED);
        }
    }

    /** 只在这个进程里读一次；两个入口 Activity 都靠它，别各自读各自的 */
    static void ensureMarks() {
        if (!marksLoaded) {
            marksLoaded = true;
            loadMarks();
        }
    }

    /** 从设备上重新读一遍标记；顺带把「我们装过分身、但还没标记」的老空间补上标记 */
    static void loadMarks() {
        final String out = Shell.exec(
                "for u in $(cmd user list --all -v 2>/dev/null | grep 'type=profile[.]CLONE'"
                + " | sed -n 's/^[0-9]*: id=\\([0-9]*\\),.*/\\1/p'); do "
                + "echo \"CSPACE $u\"; "
                + "m=$(settings get --user $u secure wb_clone 2>/dev/null); "
                + "case \"$m\" in 1*) echo \"MARK $u\";; esac; done; ");
        Set<Integer> now = new HashSet<Integer>();
        List<Integer> all = new ArrayList<Integer>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            try {
                if (t.startsWith("MARK ")) now.add(Integer.valueOf(t.substring(5).trim()));
                else if (t.startsWith("CSPACE ")) all.add(Integer.valueOf(t.substring(7).trim()));
            } catch (Throwable ignored) {
            }
        }
        synchronized (MARKED) {
            MARKED.clear();
            MARKED.addAll(now);
        }
        // 老空间（本版本之前建的，没写 settings 标记）：只有我们确实往里装过分身才认，
        // 凭 flags 猜会把 ROM 自带的分身认成我们的。
        for (Integer u : all) {
            if (marked(u.intValue())) continue;
            Set<String> kept = keptPkgs(appCtx, u.intValue());
            if (kept != null && !kept.isEmpty()) markOurs(u.intValue());
        }
    }

    static void markOurs(int uid) {
        synchronized (MARKED) {
            MARKED.add(Integer.valueOf(uid));
        }
        Shell.exec("settings put --user " + uid + " secure wb_clone 1");
        // 立刻把它排进序号表，否则新建的空间要等下一次扫描才会显示成「空间 N」，
        // 中间这段会退回到系统名/id（用户看到的就是「又变成空间 20」）。
        java.util.List<Integer> ord = new ArrayList<Integer>(SPACE_ORDER);
        if (!ord.contains(Integer.valueOf(uid))) {
            ord.add(Integer.valueOf(uid));
            SPACE_ORDER = ord;
        }
    }

    static void unmarkOurs(int uid) {
        synchronized (MARKED) {
            MARKED.remove(Integer.valueOf(uid));
        }
        Shell.exec("settings delete --user " + uid + " secure wb_clone");
    }

    /** 建空间用的系统用户名：直接写用户起的名字（不带 WB_ 前缀，第三方可见、可区分） */
    static String wbName(String base) {
        String b = base == null ? "" : base.replace("'", "").trim();
        if (b.startsWith(TAG)) b = b.substring(TAG.length()).trim();
        if (b.length() > 20) b = b.substring(0, 20);
        return b.isEmpty() ? "space" : b;
    }

    /** 这个 CLONE 空间是不是我们建的 */
    static boolean isOurs(String sysName, int uid) {
        // 传 null 的调用方（删分身、回收空间等）用扫描时缓存下来的系统用户名兜底，
        // 只看标记会把「名字带 WB_ 但我们没来得及写 settings」的空间当成别人的（变只读）
        if (sysName == null) sysName = SPACE_NAMES.get(Integer.valueOf(uid));
        if (sysName != null && sysName.startsWith(TAG)) return true;
        return marked(uid);
    }

    /**
     * 一次 shell 把所有 profile.CLONE 空间的数据端上来：「SPACE / FSPACE uid」+ 包 + 图标。
     * 归属由 Java 侧按 $OURS 分类，shell 不看 flags。
     * ⚠别用 awk：Shizuku 起的 sh 里没有 awk，整条命令会静默失败、列表全空。
     */
    private static final String SCAN =
            "RM=\" $(dumpsys user 2>/dev/null"
            + " | sed -n 's/.*UserInfo{\\([0-9]*\\):.*<removing>.*/\\1/p' | tr '\\n' ' ') \"; "
            + "cmd user list --all -v 2>/dev/null | grep 'type=profile[.]CLONE' | while read -r ln; do "
            + "u=$(echo \"$ln\" | sed -n 's/^[0-9]*: id=\\([0-9]*\\),.*/\\1/p'); "
            + "case \"$RM\" in *\" $u \"*) continue;; esac; "
            + "case \" $OURS \" in *\" $u \"*) echo \"SPACE $u\"; t=ICO;; "
            + "*) echo \"FSPACE $u\"; t=EXT;; esac; "
            + "pm list packages -e --user $u | sed \"s/^package:/PKG $u /\"; "
            + "pm list packages -d --user $u | sed \"s/^package:/OFF $u /\"; "
            + "cmd package query-activities --user $u"
            + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
            + " | sed -n \"s/.*packageName=\\([^ ]*\\).*/$t $u \\1/p\" | sort -u; done; ";

    /** 拼上「我们建的空间」清单再跑 SCAN */
    static String listBatch(Context c) {
        attach(c);
        StringBuilder sb = new StringBuilder("OURS=\" ");
        for (Integer u : markedUids()) sb.append(u).append(' ');
        sb.append("\"; ");
        return sb.toString() + SCAN;
    }

    private void refresh() {
        if (!Shell.ready()) {        // 权限还没探完，等探完再刷
            dirty = true;
            return;
        }
        if (refreshing) return;
        refreshing = true;
        dirty = false;
        new Thread(new Runnable() {
            public void run() {
                // 先读一遍空间归属标记（WB_ 前缀 + 空间内 wb_clone）：不读就等于把所有空间当外人
                ensureMarks();
                // onResume 可能比 onCreate 的后台加载跑得还早，没元信息就什么都匹配不上
                if (metaDirty || userApps.isEmpty() || labelCache.isEmpty()) {
                    metaDirty = false;
                    loadAppMeta();
                }
                final Set<String> scope = new HashSet<String>(tab == 0 ? userApps : sysApps);
                String out = Shell.exec(listBatch(MainActivity.this));
                Set<Integer> alive = new LinkedHashSet<Integer>();
                for (String line : out.split("\\n")) {
                    String t = line.trim();
                    if (!t.startsWith("SPACE ")) continue;
                    try { alive.add(Integer.valueOf(t.substring(6).trim())); }
                    catch (Throwable ignored) { }
                }

                Map<String, List<Clone>> map = new HashMap<String, List<Clone>>();
                Map<Integer, Set<String>> keptBySpace = new HashMap<Integer, Set<String>>();
                for (Integer u : alive) {
                    keptBySpace.put(u, keptPkgs(MainActivity.this, u.intValue()));
                }
                Map<Integer, Set<String>> iconBySpace = new HashMap<Integer, Set<String>>();
                for (String line : out.split("\\n")) {
                    String t = line.trim();
                    if (!t.startsWith("ICO ")) continue;
                    String[] pp = t.split("\\s+");
                    if (pp.length < 3) continue;
                    Integer uid = Integer.valueOf(pp[1]);
                    Set<String> ic = iconBySpace.get(uid);
                    if (ic == null) { ic = new HashSet<String>(); iconBySpace.put(uid, ic); }
                    ic.add(pp[2]);
                }
                allClones.clear();
                for (String line : out.split("\\n")) {
                    String t = line.trim();
                    final boolean off = t.startsWith("OFF ");
                    final boolean ext = t.startsWith("EXT ");
                    if (!off && !ext && !t.startsWith("PKG ")) continue;
                    String[] pp = t.split("\\s+");
                    if (pp.length < 3) continue;
                    final String pkg = pp[2];
                    if (pkg.equals(getPackageName())) continue;
                    Integer uid = Integer.valueOf(pp[1]);
                    if (!ext && !alive.contains(uid)) continue;
                    if (ext) {
                        // 别人建的分身空间（一加自带 MultiApp）：只显示出来给用户看，
                        // 不参与任何清理，界面上也不给删除 —— 那是人家的地盘
                        // 归哪个页签按包本身判断：主身份认不出这个包时就当三方，别让它消失
                        boolean third = userApps.contains(pkg) || !sysApps.contains(pkg);
                        Clone c = new Clone();
                        c.uid = uid.intValue();
                        c.profile = "space";
                        c.foreign = true;
                        allClones.put(uid + ":" + pkg, c);
                        if ((tab == 0) == third) {
                            List<Clone> lo = map.get(pkg);
                            if (lo == null) { lo = new ArrayList<Clone>(); map.put(pkg, lo); }
                            lo.add(c);
                        }
                        continue;
                    }
                    Set<String> kept = keptBySpace.get(uid);
                    // 谁算「这个空间里真有这个分身」：登记表里的算；不在登记表但「第三方
                    // 且有桌面图标」的也算——第三方「数据备份」恢复进来的应用不在登记表，
                    // 但确实在空间里，必须显示（否则恢复的微信在首页看不到）。
                    if (kept == null || !kept.contains(pkg)) {
                        if (!THIRD.contains(pkg)) continue;
                        Set<String> ic = iconBySpace.get(uid);
                        if (ic == null || !ic.contains(pkg)) continue;
                    }
                    Clone c = new Clone();
                    c.uid = uid.intValue();
                    c.profile = "space";
                    c.off = off;
                    // 最近打开要反查「任何页签」的分身，所以先记全量，再按页签过滤进列表
                    allClones.put(uid + ":" + pkg, c);
                    if (!scope.contains(pkg)) continue;
                    List<Clone> l = map.get(pkg);
                    if (l == null) { l = new ArrayList<Clone>(); map.put(pkg, l); }
                    l.add(c);
                }

                // 主界面列「所有可分身的三方应用」，有分身的把分身挂在下面
                final List<Group> got = new ArrayList<Group>();
                Set<String> all = new HashSet<String>(scope);
                all.addAll(map.keySet());
                for (String pkg : all) {
                    // 只有「主身份认不出、但确实有分身」的包才用包名兜底：
                    // 以前这里直接 continue，这类包（比如只装在别人空间里的）会整条消失。
                    if (!labelCache.containsKey(pkg) && !map.containsKey(pkg)) continue;
                    Group g = new Group();
                    g.pkg = pkg;
                    g.label = labelCache.get(pkg);
                    if (g.label == null) g.label = pkg;    // 元信息里没有就用包名，至少能看见、能点
                    g.icon = iconCache.get(pkg);
                    List<Clone> l = map.get(pkg);
                    if (l != null) {
                        g.clones.addAll(l);
                        Collections.sort(g.clones, new Comparator<Clone>() {
                            public int compare(Clone a, Clone b) { return a.uid - b.uid; }
                        });
                    }
                    got.add(g);
                }
                Collections.sort(got, new Comparator<Group>() {
                    public int compare(Group a, Group b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });

                runOnUiThread(new Runnable() {
                    public void run() {
                        groups.clear();
                        groups.addAll(got);
                        render();
                        cleanStaleShortcuts(MainActivity.this);
                        refreshing = false;
                        if (dirty) refresh();
                    }
                });
            }
        }).start();
    }

    // ---------------- 渲染 ----------------

    /** 当前是否深色。以用户选的模式为准，系统夜间模式兜底；黑色也算深色 */
    private boolean isNight() {
        int t = sp().getInt("theme", THEME_AUTO);
        if (t == THEME_DARK) return true;
        if (t == THEME_LIGHT) return false;
        return (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 纯黑（AMOLED）：只在深色模式下生效 */
    private boolean isBlack() {
        return isNight() && sp().getBoolean("amoled", false);
    }

    // ---------------- 网格（启动器式） ----------------

    private boolean gridMode() { return sp().getBoolean("grid_view", false); }

    private void setGridMode(boolean on) {
        sp().edit().putBoolean("grid_view", on).apply();
        updateGridBtn();
    }

    private int gridCols() { return clamp(sp().getInt("grid_cols", 5), 3, 8); }

    private int gridRows() { return clamp(sp().getInt("grid_rows", 7), 3, 16); }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** 横屏时行列对调：屏幕变宽、变矮，用「行数当列数、列数当行数」格子才不会变形 */
    private boolean landscape() {
        return getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private int effCols() { return landscape() ? gridRows() : gridCols(); }

    private int effRows() { return landscape() ? gridCols() : gridRows(); }

    /** 最近打开显示开关：关了所有视图都不显示 */
    private boolean recentShow() { return sp().getBoolean("recent_show", true); }

    /** 最近打开每行几个：0=跟随网格列数 */
    private int recentCols() {
        int v = sp().getInt("recent_cols", 0);
        return v > 0 ? clamp(v, 3, 8) : effCols();
    }

    /** 最近打开最多几行：默认 1 行，超出的不显示 */
    private int recentRows() { return clamp(sp().getInt("recent_rows", 1), 1, 4); }

    /** 格子高度 =（网格可视高 - 最近区高）/ 行数；下限要装得下图标+名字（44+3+文字） */
    private int computeItemH() {
        int h = gridWrap.getHeight();
        if (recentHeader != null && recentHeader.getParent() == gridBox) {
            h -= recentHeader.getHeight();
        }
        if (h <= 0) return dp(84);
        return Math.max(dp(66), h / effRows() - dp(4));
    }

    private void updateGridBtn() {
        if (btnGrid == null) return;
        boolean on = gridMode();
        btnGrid.setImageResource(on ? R.drawable.ic_list : R.drawable.ic_grid);
        btnGrid.setContentDescription(getString(on ? R.string.list_view : R.string.grid_view));
    }

    /** 网格平铺项列表：renderGrid 构建，buildGrid 铺格子 */
    private final List<FlatItem> flatItems = new ArrayList<FlatItem>();

    /** 网格模式只渲染网格；列表走 render() 下面原逻辑 */
    private void renderGrid() {
        listWrap.setVisibility(View.GONE);
        emptyView.setVisibility(View.GONE);
        gridWrap.setVisibility(View.VISIBLE);

        Collections.sort(groups, new Comparator<Group>() {
            public int compare(Group a, Group b) {
                boolean ca = !a.clones.isEmpty(), cb = !b.clones.isEmpty();
                if (ca != cb) return ca ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        boolean showAll = sp().getBoolean("show_all", false);
        boolean showPrimary = sp().getBoolean("grid_show_primary", false);
        flatItems.clear();
        for (Group g : groups) {
            if (!showAll && g.clones.isEmpty()) continue;
            if (kw.length() > 0
                    && !g.label.toLowerCase().contains(kw)
                    && !g.pkg.toLowerCase().contains(kw)) {
                continue;
            }
            if (showPrimary) flatItems.add(new FlatItem(g, null, -1));
            for (int i = 0; i < g.clones.size(); i++) {
                flatItems.add(new FlatItem(g, g.clones.get(i), i));
            }
        }

        if (flatItems.isEmpty()) {
            gridWrap.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        // 可视高度要等本帧布局完成才有，post 到下一帧铺格子
        gridWrap.post(new Runnable() {
            public void run() { buildGrid(); }
        });
    }

    /** 铺自绘网格：每行 effCols() 个格子，固定宽度左对齐（最后一行不满也靠左，跟启动器一致） */
    private void buildGrid() {
        gridBox.removeAllViews();
        if (recentShow() && refreshRecentHeader()) {
            gridBox.addView(recentHeader);   // 挂进滚动内容：上滑跟着滚走
        }
        // 最近区刚挂上还没 measure，等一帧再算格子高度（computeItemH 要减它的高度）
        gridWrap.post(new Runnable() { public void run() { buildCells(); } });
    }

    private void buildCells() {
        gridItemH = computeItemH();
        LayoutInflater inf = LayoutInflater.from(this);
        int cols = effCols();
        int avail = gridBox.getWidth() - gridBox.getPaddingLeft() - gridBox.getPaddingRight();
        int cellW = avail > 0 ? avail / cols : dp(80);
        if (cellW < 1) cellW = 1;
        for (int start = 0; start < flatItems.size(); start += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int end = Math.min(start + cols, flatItems.size());
            for (int p = start; p < end; p++) {
                View cell = inf.inflate(R.layout.item_grid, row, false);
                bindCell(cell, flatItems.get(p));
                cell.setLayoutParams(new LinearLayout.LayoutParams(cellW, gridItemH));
                row.addView(cell);
            }
            gridBox.addView(row);
        }
    }

    /** 一个格子的内容与交互（点开 / 长按出菜单） */
    private void bindCell(View v, final FlatItem it) {
        final Group g = it.g;
        ImageView icon = v.findViewById(R.id.grid_icon);
        TextView name = v.findViewById(R.id.grid_name);
        boolean dark = isNight();
        int nameColor = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        if (it.c == null) {
            if (g.icon != null) icon.setImageDrawable(g.icon);
            name.setText(g.label);
            name.setTextColor(nameColor);
            icon.setAlpha(1f);
        } else {
            final Clone c = it.c;
            if (g.icon != null) icon.setImageBitmap(badge(g.icon, it.idx));
            String nm = g.label + suffixOf(c.uid, g.pkg, it.idx);
            if (c.off) {
                nm = nm + " · " + getString(R.string.clone_hidden);
                name.setTextColor(dark ? 0xFF7A7A7A : 0xFFA0A0A0);
                icon.setAlpha(0.4f);
            } else {
                name.setTextColor(nameColor);
                icon.setAlpha(1f);
            }
            name.setText(nm);
        }
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View vv) { onGridClick(it); }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View vv) { onGridLongClick(it); return true; }
        });
    }

    private void onGridClick(FlatItem it) {
        if (it.c == null) { openPrimary(it.g); return; }
        if (it.c.off) { toast(getString(R.string.clone_hidden_hint)); return; }
        launch(it.c, it.g);
    }

    private void onGridLongClick(FlatItem it) {
        if (it.c == null) {
            setGridMode(false);
            multi = true;
            selected.clear();
            selected.add(it.g.pkg);
            multiBar.setVisibility(View.VISIBLE);
            render();
            return;
        }
        cloneMenu(it.c, it.g);
    }

    private void render() {
        if (gridMode()) {
            renderGrid();
            return;
        }
        gridWrap.setVisibility(View.GONE);
        listWrap.setVisibility(View.VISIBLE);
        listBox.removeAllViews();
        if (recentShow() && refreshRecentHeader()) {
            listBox.addView(recentHeader);   // 挂进滚动内容：上滑跟着滚走
        }
        // 已经开过分身的应用排最前，其余按名字排
        Collections.sort(groups, new Comparator<Group>() {
            public int compare(Group a, Group b) {
                boolean ca = !a.clones.isEmpty(), cb = !b.clones.isEmpty();
                if (ca != cb) return ca ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        boolean empty = groups.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listBox.setVisibility(empty ? View.GONE : View.VISIBLE);

        LayoutInflater inf = LayoutInflater.from(this);
        boolean showAll = sp().getBoolean("show_all", false);
        final boolean dark = isNight();
        final boolean black = isBlack();
        final int cardColor = black ? 0xFF0C0C0C : (dark ? 0xFF1E1F22 : 0xFFF3F3F6);
        final int btnBg = black ? 0xFF16283D : (dark ? 0xFF1F3A5F : 0xFFD3E3FD);
        final int btnText = dark ? 0xFFA8C7FA : 0xFF0B57D0;
        final int nameColor = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        int shown = 0;
        for (final Group g : groups) {
            // 默认只列已经开过分身的应用，避免一屏全是没动过的 App
            // 多选批量创建时列全部可分身应用（不只已开分身的），否则勾不到还没开过的应用
            if (!multi && !showAll && g.clones.isEmpty()) continue;
            if (kw.length() > 0
                    && !g.label.toLowerCase().contains(kw)
                    && !g.pkg.toLowerCase().contains(kw)) {
                continue;
            }
            shown++;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            if (card.getBackground() != null) card.getBackground().setTint(cardColor);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = dp(8);
            card.setLayoutParams(clp);
            card.setPadding(0, dp(2), 0, dp(2));

            // 第一行：主身份那份 + 「创建分身」
            View base = inf.inflate(R.layout.item_clone_base, card, false);
            ripple(base);
            ImageView bi = base.findViewById(R.id.base_icon);
            if (g.icon != null) bi.setImageDrawable(g.icon);
            ((TextView) base.findViewById(R.id.base_name)).setText(g.label);
            ((TextView) base.findViewById(R.id.base_name)).setTextColor(nameColor);
            final android.widget.CheckBox baseCheck = base.findViewById(R.id.base_check);
            android.widget.Button act = base.findViewById(R.id.action_add);
            act.setBackgroundTintList(android.content.res.ColorStateList.valueOf(btnBg));
            act.setTextColor(btnText);
            if (multi) {
                // 多选模式：显示勾选框，点行切换选中，隐藏「创建分身」按钮
                baseCheck.setVisibility(View.VISIBLE);
                act.setVisibility(View.GONE);
                baseCheck.setChecked(selected.contains(g.pkg));
                base.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boolean on = !selected.contains(g.pkg);
                        if (on) selected.add(g.pkg);
                        else selected.remove(g.pkg);
                        baseCheck.setChecked(on);
                        updateCount();
                    }
                });
                base.setOnLongClickListener(null);
            } else {
                // 普通模式：整行点开原应用，长按进入多选
                baseCheck.setVisibility(View.GONE);
                act.setVisibility(View.VISIBLE);
                act.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { createClone(g); }
                });
                base.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { openPrimary(g); }
                });
                base.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        multi = true;
                        selected.clear();
                        selected.add(g.pkg);
                        multiBar.setVisibility(View.VISIBLE);
                        render();
                        return true;
                    }
                });
            }
            card.addView(base);

            // 下面：每个分身一行（多选批量创建时不显示分身行，只留主应用行）
            for (int i = 0; i < g.clones.size() && !multi; i++) {
                final Clone c = g.clones.get(i);
                View row = inf.inflate(R.layout.item_clone_child, card, false);
                ripple(row);
                ImageView ci = row.findViewById(R.id.clone_icon);
                if (g.icon != null) ci.setImageBitmap(badge(g.icon, i));
                TextView cn = row.findViewById(R.id.clone_name);
                String nm = g.label + suffixOf(c.uid, g.pkg, i);
                if (c.off) {
                    // 被隐藏的分身仍在列表里，只是灰掉 —— 免得用户以为它消失了，
                    // 也留一条「长按启用」的路（万一那个系统应用其实还需要）
                    nm = nm + " · " + getString(R.string.clone_hidden);
                    cn.setTextColor(dark ? 0xFF7A7A7A : 0xFFA0A0A0);
                    ci.setAlpha(0.4f);
                } else {
                    cn.setTextColor(nameColor);
                }
                cn.setText(nm);
                // 跑马灯只对 selected 的 TextView 生效；超长名字（比如改成了长串）就自己滚。
                // 挂到窗口之后再设一次：render 时控件还没 attach，只设一次不一定会启动动画。
                cn.setSingleLine(true);
                cn.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
                cn.setSelected(true);
                cn.post(new Runnable() {
                    public void run() { cn.setSelected(true); }
                });
                TextView cu = row.findViewById(R.id.clone_uid);
                if (cu != null) cu.setText(getString(R.string.uid_tag, c.uid));
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (c.off) {
                            toast(getString(R.string.clone_hidden_hint));
                            return;
                        }
                        launch(c, g);
                    }
                });
                row.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        cloneMenu(c, g);
                        return true;
                    }
                });
                final int idx = i;
                // 非自建空间（MultiApp 等）：默认只读，不给改名/移除；开了「管理非自建空间」才放开
                if (c.foreign && !sp().getBoolean("manage_foreign", false)) {
                    row.findViewById(R.id.clone_rename).setVisibility(View.GONE);
                    row.findViewById(R.id.clone_delete).setVisibility(View.GONE);
                }
                int iconTint = dark ? 0xFF9AA0A6 : 0xFF5F6368;
                for (int rid : new int[]{R.id.clone_pin, R.id.clone_open,
                        R.id.clone_rename, R.id.clone_delete}) {
                    ImageView iv = row.findViewById(rid);
                    if (iv != null) iv.setColorFilter(iconTint);
                }
                row.findViewById(R.id.clone_pin).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        pinShortcut(MainActivity.this, c.uid, g.pkg,
                                g.label + suffixOf(c.uid, g.pkg, idx),
                                g.icon, nameOf(MainActivity.this, c.uid));
                    }
                });
                ImageView open = row.findViewById(R.id.clone_open);
                open.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { launch(c, g); }
                });
                row.findViewById(R.id.clone_rename).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { askRename(c, g, idx); }
                });
                row.findViewById(R.id.clone_delete).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { confirmRemove(c, g); }
                });
                card.addView(row);
            }

            listBox.addView(card);
        }

        if (shown == 0) {
            TextView tv = new TextView(this);
            String t;
            if (kw.length() > 0) t = getString(R.string.empty_no_match, kw);
            else if (!showAll) t = getString(R.string.empty_no_clone);
            else t = getString(R.string.empty_no_app);
            tv.setText(t);
            tv.setTextSize(14);
            tv.setTextColor(0xFF888888);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, dp(60), 0, 0);
            listBox.addView(tv);
        }
        if (multi) updateCount();
    }

    /** 多选计数 */
    private void updateCount() {
        if (multiCount != null) multiCount.setText(getString(R.string.selected_n, selected.size()));
    }

    /** 第二级：挑目标空间（可多选，也可勾「新建一个空间」） */
    private void showSpacePicker() {
        say("正在读取分身空间…");
        new Thread(new Runnable() {
            public void run() {
                final List<Integer> spaces = listSpaceUids();
                runOnUiThread(new Runnable() {
                    public void run() {
                        final String[] items = new String[spaces.size() + 1];
                        for (int i = 0; i < spaces.size(); i++) {
                            int u = spaces.get(i).intValue();
                            Set<String> k = keptPkgs(MainActivity.this, u);
                            items[i] = nameOf(MainActivity.this, u) + " · "
                                    + (k == null ? 0 : k.size()) + " 个分身";
                        }
                        items[spaces.size()] = getString(R.string.new_space_option);
                        final boolean[] checked = new boolean[items.length];
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.pick_spaces_title)
                                .setMultiChoiceItems(items, checked,
                                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                                            public void onClick(android.content.DialogInterface d,
                                                    int which, boolean isChecked) {
                                                checked[which] = isChecked;
                                            }
                                        })
                                .setPositiveButton(android.R.string.ok,
                                        new android.content.DialogInterface.OnClickListener() {
                                            public void onClick(android.content.DialogInterface d, int w) {
                                                List<Integer> targets = new ArrayList<Integer>();
                                                for (int i = 0; i < spaces.size(); i++) {
                                                    if (checked[i]) targets.add(spaces.get(i));
                                                }
                                                boolean wantNew = checked[spaces.size()];
                                                if (targets.isEmpty() && !wantNew) {
                                                    toast(getString(R.string.batch_none_selected));
                                                    return;
                                                }
                                                doBatch(new ArrayList<String>(selected), targets, wantNew);
                                            }
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 对「应用 × 空间」逐个安装，装过的跳过，最后汇总 */
    private void doBatch(final List<String> pkgs, final List<Integer> targets,
            final boolean wantNew) {
        say("正在批量创建分身…");
        multiBar.setEnabled(false);
        if (wantNew) {
            askSpaceName(new NameCb() {
                public void onName(String name) {
                    doBatchInner(pkgs, targets, true, name);
                }
            }, new Runnable() {
                public void run() {
                    multiBar.setEnabled(true);
                    multi = false;
                    selected.clear();
                    multiBar.setVisibility(View.GONE);
                    render();
                }
            });
        } else {
            doBatchInner(pkgs, targets, false, "");
        }
    }

    private void doBatchInner(final List<String> pkgs, final List<Integer> targets,
            final boolean wantNew, final String name) {
        new Thread(new Runnable() {
            public void run() {
                final List<Integer> t = new ArrayList<Integer>(targets);
                String newErr = null;
                if (wantNew) {
                    String out = Shell.exec(WB + " create '" + wbName(name) + "'");
                    Matcher m = Pattern.compile("\\{(\\d+):").matcher(out);
                    int got = -1;
                    if (m.find()) got = Integer.parseInt(m.group(1));
                    if (got > 0) {
                        markOurs(got);
                        Launcher.hideSelfFrom(got);
                        t.add(Integer.valueOf(got));
                        if (!keepSys()) pruneSpace(MainActivity.this, got, true);
                    } else {
                        newErr = out.contains("Cannot add more")
                                ? getString(R.string.space_limit_one)
                                : getString(R.string.create_failed, out.trim());
                    }
                }
                int ok = 0, skip = 0, fail = 0;
                for (String pkg : pkgs) {
                    for (int uid : t) {
                        Set<String> kept = keptPkgs(MainActivity.this, uid);
                        if (kept != null && kept.contains(pkg)) {
                            skip++;
                            continue;
                        }
                        String ir = Launcher.installExisting(uid, pkg);
                        String low = ir.toLowerCase();
                        boolean good = low.contains("installed") || low.contains("success");
                        if (good) {
                            markKept(MainActivity.this, uid, pkg);
                            ok++;
                        } else {
                            fail++;
                        }
                    }
                }
                dirty = true;
                final int fok = ok, fskip = skip, ffail = fail;
                final String fnewErr = newErr;
                runOnUiThread(new Runnable() {
                    public void run() {
                        multiBar.setEnabled(true);
                        String msg = getString(R.string.batch_result, fok, fskip, ffail);
                        if (fnewErr != null) msg += "\n" + fnewErr;
                        say(msg);
                        toast(msg);
                        multi = false;
                        selected.clear();
                        multiBar.setVisibility(View.GONE);
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 在应用图标右下角画一个彩色小方块当分身标记 */
    private Bitmap badge(Drawable base, int idx) {
        int size = dp(44);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        base.setBounds(0, 0, size, size);
        base.draw(c);
        int bw = size / 3;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(BADGE[idx % BADGE.length]);
        float r = bw / 5f;
        c.drawRoundRect(size - bw, size - bw, size, size, r, r, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(size - bw / 2f, size - bw / 2f, bw / 6f, p);
        return bm;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------- 动作 ----------------

    /**
     * 给这个应用建分身。
     *
     * 两条路：先按 myui 的方式新建一个 clone profile（moto 上最多 5 个）；
     * 建不出来（红米/LOS 上 perParent=1，已经占满）就复用已有的分身空间，
     * 把应用装进去 —— 一个空间装多个应用，这是 AOSP 允许的做法。
     */
    private interface NameCb { void onName(String name); }

    private void createClone(final Group g) {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say(getString(R.string.creating_clone, g.label));
        new Thread(new Runnable() {
            public void run() {
                // 先把应用塞进已有空间里还没装它的那个（红米/LOS 只允许 1 个空间，只能这样）
                int spare = pickReusableSpace(MainActivity.this, g.pkg);
                if (spare > 0) {
                    installIntoClone(g, spare, true, "");
                    return;
                }
                // 每个空间里都已经有它了（想要再开一份），才新建一个空间
                int per = maxPerParent();
                int have = countSpaces();
                if (per > 0 && have >= per) {
                    final String msg = getString(R.string.space_full, String.valueOf(per));
                    runOnUiThread(new Runnable() {
                        public void run() { say(msg); }
                    });
                    return;
                }
                // 新建空间前让用户起名：名字进系统用户名（WB_ 前缀），第三方可见、可区分
                runOnUiThread(new Runnable() {
                    public void run() {
                        askSpaceName(new NameCb() {
                            public void onName(final String name) {
                                new Thread(new Runnable() {
                                    public void run() {
                                        String out = Shell.exec(WB + " create '" + wbName(name) + "'");
                                        Matcher m = Pattern.compile("\\{(\\d+):").matcher(out);
                                        int uid = m.find() ? Integer.parseInt(m.group(1)) : -1;
                                        if (uid <= 0) {
                                            final String msg = failMsg(out.trim());
                                            runOnUiThread(new Runnable() {
                                                public void run() { say(msg); }
                                            });
                                            return;
                                        }
                                        installIntoClone(g, uid, false, name);
                                    }
                                }).start();
                            }
                        }, null);
                    }
                });
            }
        }).start();
    }

    /** 弹框给新空间起名；确定后回调名字（可能为空，空则系统名回落 WB_space）；取消回调 onCancel（可空） */
    private void askSpaceName(final NameCb cb, final Runnable onCancel) {
        final EditText et = new EditText(this);
        et.setHint(R.string.new_space_name);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_new_space)
                .setView(et)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        cb.onName(et.getText().toString().trim());
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (onCancel != null) onCancel.run();
                    }
                })
                .show();
    }

    /** 拿到空间 uid 后：登记归属 → 装应用 → 精简 → 反馈 */
    private void installIntoClone(final Group g, final int uid, final boolean reused,
            final String name) {
        markOurs(uid);          // 归属登记：以后靠它 + 名字前缀认自己人
        // 不主动 start：建号时系统已经把档案拉起来了，这里再 start 只会让桌面
        // 把「刚灌进来还没删的那批应用」bind 一遍
        Launcher.hideSelfFrom(uid);
        final String ir = Launcher.installExisting(uid, g.pkg);
        // ⚠pm install-existing 成功时输出 "Package X installed for user: N"，没有 success 字样。
        // 以前只认 success ⇒ 每次创建都被当成失败：不登记、不清理、界面还报「没装进分身」
        final String low = ir.toLowerCase();
        final boolean installed = low.contains("installed") || low.contains("success");
        // 登记：这个空间里「该有」的包。清理只认这张表，不再靠「三方还是系统」猜
        if (installed) markKept(MainActivity.this, uid, g.pkg);
        // 新空间会被系统灌进主身份里的全部应用（OP12 上 25 个三方全来了、红米 184 个包），
        // 一加桌面会把它们整个摆出来 ⇒ 建完立刻按登记表清掉，只留刚装进去的这个
        int left = 0;
        if (!reused && installed && !keepSys()) {
            left = pruneSpace(MainActivity.this, uid, true);
        }
        final int fu = uid;
        final boolean fre = reused;
        final int fleft = left;
        runOnUiThread(new Runnable() {
            public void run() {
                if (!installed) say(getString(R.string.install_failed,
                        g.label, ir.trim()));
                else {
                    String msg = fre
                            ? getString(R.string.clone_reused, g.label, fu)
                            : getString(R.string.clone_created, g.label, fu);
                    if (fleft > 0) {
                        // 删不掉就照实说，别让用户以为清干净了
                        msg += getString(R.string.prune_failed, String.valueOf(fleft));
                    }
                    say(msg);
                }
                refresh();
                if (autoIcon()) {
                    pinShortcut(MainActivity.this, fu, g.pkg,
                            g.label + suffixOf(fu, g.pkg, g.clones.size()),
                            g.icon, nameOf(MainActivity.this, fu));
                }
            }
        });
    }

    // ---------------- 分身空间登记表 ----------------
    //
    // 新用户一建出来，系统会把主身份装过的所有应用都给它装一份 —— 红米 184 个包、
    // OP12 上 25 个三方应用全进来。所以「空间里装了什么」根本不能当作「用户要分身什么」，
    // 必须自己记：只有我们 install-existing 进去的包才算数。

    /** 某空间登记的包；null 表示老版本建的空间，没登记过 */
    static Set<String> keptPkgs(Context c, int uid) {
        try {
            return c.getSharedPreferences("clone", Context.MODE_PRIVATE)
                    .getStringSet("space_pkgs_" + uid, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这个空间里还剩几个「带桌面图标的三方应用」。用来判断空间是不是真的空了。
     * 返回 -1 ＝ 判不了（主身份应用清单还没加载完），调用方不许据此下结论。
     */
    static int iconsInSpace(int uid) {
        if (THIRD.isEmpty()) return -1;
        String out = Shell.exec("cmd package query-activities --user " + uid
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " | sed -n 's/.*packageName=\\([^ ]*\\).*/\\1/p' | sort -u");
        int n = 0;
        for (String p : out.split("\n")) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (appCtx != null && t.equals(appCtx.getPackageName())) continue;
            if (THIRD.contains(t)) n++;
        }
        return n;
    }

    static void markKept(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("space_pkgs_" + uid, null);
        Set<String> s = old == null ? new HashSet<String>() : new HashSet<String>(old);
        if (!s.add(pkg)) return;
        sp.edit().putStringSet("space_pkgs_" + uid, s).apply();
    }

    static void unmarkKept(Context c, int uid, String pkg) {
        SharedPreferences sp = c.getSharedPreferences("clone", Context.MODE_PRIVATE);
        Set<String> old = sp.getStringSet("space_pkgs_" + uid, null);
        if (old == null) return;
        Set<String> s = new HashSet<String>(old);
        if (!s.remove(pkg)) return;
        sp.edit().putStringSet("space_pkgs_" + uid, s).apply();
    }

    /** 空间名（建空间时给的名字），用来给老空间猜「这个空间本来是给谁建的」 */
    static String spaceName(int uid) {
        String out = Shell.exec("cmd user list --all -v 2>/dev/null");
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.contains("type=profile.CLONE") || !t.contains("id=" + uid + ",")) continue;
            int i = t.indexOf("name=");
            int j = i < 0 ? -1 : t.indexOf(',', i);
            if (j > i) return t.substring(i + 5, j).trim();
        }
        return null;
    }

    /** 自己给过桌面图标的包，同样算用户要的 */
    static Set<String> shortcutPkgs(Context c, int uid) {
        Set<String> out = new HashSet<String>();
        try {
            ShortcutManager sm = (ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) return out;
            String prefix = "clone_" + uid + "_";
            for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                String id = si.getId();
                if (id != null && id.startsWith(prefix)) out.add(id.substring(prefix.length()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 老空间没登记表时的兜底：空间名 == 应用名，就当这个应用是用户要的 */
    static Set<String> sameNamePkgs(Context c, int uid) {
        Set<String> out = new HashSet<String>();
        String name = spaceName(uid);
        if (name == null || name.length() == 0) return out;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                if (ri.activityInfo == null) continue;
                if (name.equals(String.valueOf(ri.loadLabel(pm)))) {
                    out.add(ri.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 分身里的隐式 Intent 要靠这些应用解析：浏览器（点链接）、看图、文件选择器。
     * 默认打开方式是按用户隔离的 —— 分身里一个解析器都没装的话，
     * 应用点个链接就是 "No activity found"，所以这几个必须留在空间里。
     */
    static Set<String> resolverPkgs(Context c) {
        Set<String> out = new HashSet<String>();
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            addResolver(pm, out, new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("http://example.com/")));
            addResolver(pm, out, new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("content://media/external/images/media/1"))
                    .setType("image/jpeg"));
            addResolver(pm, out, new Intent(Intent.ACTION_SEND).setType("text/plain"));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addResolver(android.content.pm.PackageManager pm, Set<String> out,
                                    Intent i) {
        try {
            android.content.pm.ResolveInfo ri =
                    pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) out.add(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
        }
    }

    /** 与 prune 脚本里的白名单保持一致（Java 侧判断用） */
    static boolean isEssentialPkg(String p) {
        if (p.equals("com.cubsync.cubclone")) return true;
        return p.endsWith(".documentsui") || p.contains(".camera") || p.contains("contacts")
                || p.contains("dialer") || p.contains("messaging")
                || p.contains("providers.media");
    }

    /** 分身里没它们就用不了的东西：输入法、Play 内购、WebView、GMS */
    static Set<String> mustKeep(Context c) {
        Set<String> out = new HashSet<String>();
        out.add("com.google.android.gms");
        out.add("com.google.android.gsf");
        out.add("com.android.vending");
        out.add("com.android.webview");
        out.add("com.google.android.webview");
        out.add("com.android.inputmethod.latin");
        try {
            android.content.ContentResolver cr = c.getContentResolver();
            addIme(out, android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD));
            String all = android.provider.Settings.Secure.getString(
                    cr, android.provider.Settings.Secure.ENABLED_INPUT_METHODS);
            if (all != null) {
                for (String s : all.split(":")) addIme(out, s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addIme(Set<String> out, String comp) {
        if (comp == null) return;
        int i = comp.indexOf('/');
        String p = (i > 0 ? comp.substring(0, i) : comp).trim();
        if (p.length() > 0) out.add(p);
    }

    /**
     * 清掉分身空间里「用户没要」的东西。
     *
     * 判据＝登记表（＋给过桌面图标的包）；不再按「三方 / 系统」猜 —— 一加那种
     * 连主身份里 25 个三方应用一起灌进新空间的系统，按老判据一个都删不掉。
     *
     * 两遍：① 所有带桌面图标的包，只留登记表 / 解析器 / 输入法 / Play 这些；
     *       ② 再扫一遍三方包，连**没有桌面图标**的那些也删（它们只是被灌进来的，
     *          留着会在分身里偷偷起服务、收推送）。
     *
     * @param fresh true＝这个空间刚建出来（不可能有用户数据），登记表为空也照删；
     *              false＝清理老空间，什么线索都没有就不动，宁可留一堆也不误删。
     * @return 重试一次后仍然没删掉的包数
     * ⚠只 `pm uninstall --user <uid>`，主身份那份毫发无损，随时可 install-existing 装回。
     */
    static int pruneSpace(Context c, int uid, boolean fresh) {
        if (!Launcher.safe(uid)) return 0;
        Set<String> rec = keptPkgs(c, uid);
        Set<String> keep = rec == null ? new HashSet<String>() : new HashSet<String>(rec);
        keep.addAll(shortcutPkgs(c, uid));
        if (!fresh) keep.addAll(sameNamePkgs(c, uid));
        // 解析器 / 输入法 / Play 一律留着，否则分身里打不了字、点链接没反应
        keep.addAll(resolverPkgs(c));
        keep.addAll(mustKeep(c));
        StringBuilder kp = new StringBuilder(" ");
        for (String p : keep) kp.append(p).append(' ');
        String q = String.valueOf((char) 34);      // "
        final String ESS = "com.cubsync.cubclone|*.documentsui|*.camera*|*contacts*|*dialer*"
                + "|*messaging*|*providers.media*";
        String cmd =
                "KP=" + q + kp + q + "; DL=" + q + " " + q + "; "
                + "un() { case " + q + "$KP" + q + " in *" + q + " $1 " + q + "*) return 0;; esac; "
                + "case " + q + "$DL" + q + " in *" + q + " $1 " + q + "*) return 0;; esac; "
                + "case " + q + "$1" + q + " in " + ESS + ") return 0;; esac; "
                + "pm disable-user --user " + uid + " " + q + "$1" + q
                + " >/dev/null 2>&1 || echo " + q + "FAIL $1" + q + "; "
                + "DL=" + q + "$DL$1 " + q + "; }; "
                + "for p in $(cmd package query-activities --user " + uid
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                + " | sed -n " + q + "s/.*packageName=\\([^ ]*\\).*/\\1/p" + q
                + " | sort -u); do un $p; done; "
                + "for p in $(pm list packages -3 -e --user " + uid
                + " | sed " + q + "s/^package://" + q + "); do un $p; done";
        String out = Shell.exec(cmd);

        List<String> fails = new ArrayList<String>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("FAIL ")) fails.add(t.substring(5).trim());
        }
        if (fails.isEmpty()) return 0;
        // 刚建好的用户里偶发 "Failure [not installed for user]"（包正在被系统处理），重试一次
        int still = 0;
        for (String p : fails) {
            String r = Launcher.disable(uid, p).toLowerCase();
            if (r.contains("success") || r.contains("not installed")) continue;
            still++;
            android.util.Log.w("CubClone", "prune fail uid=" + uid + " " + p + " " + r.trim());
        }
        return still;
    }

    /**
     * 反向操作：把主身份里的系统应用重新装回分身空间。
     * 全部是 `pm install-existing --user`，只动该用户的安装记录，不碰主身份。
     */
    static void restoreSpace(int uid) {
        if (!Launcher.safe(uid)) return;
        Shell.exec("for p in $(pm list packages -s); do p=${p#package:}; "
                + "pm install-existing --user " + uid + " $p >/dev/null 2>&1; "
                + "pm enable --user " + uid + " $p >/dev/null 2>&1; done");
    }

    /** 还原所有分身空间（会重新出现一堆图标，仅供后悔时用） */
    private void restoreAllSpaces() {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say(getString(R.string.restoring));
        new Thread(new Runnable() {
            public void run() {
                int n = 0;
                for (int uid : listSpaceUids()) {
                    restoreSpace(uid);
                    n++;
                }
                final int fn = n;
                runOnUiThread(new Runnable() {
                    public void run() {
                        say(getString(R.string.restored, fn));
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 把现有所有空间都精简一遍（老版本建的空间里已经躺着一堆系统应用） */
    private void pruneAllSpaces() {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say(getString(R.string.pruning));
        new Thread(new Runnable() {
            public void run() {
                int n = 0, left = 0;
                for (int uid : listSpaceUids()) {
                    left += pruneSpace(MainActivity.this, uid, false);
                    n++;
                }
                final int fn = n, fl = left;
                runOnUiThread(new Runnable() {
                    public void run() {
                        String msg = getString(R.string.pruned, String.valueOf(fn));
                        if (fl > 0) msg += getString(R.string.prune_failed, String.valueOf(fl));
                        say(msg);
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 现有分身空间个数（CLONE 类型的用户） */
    private int countSpaces() {
        return listSpaceUids().size();
    }

    /** 分身空间名字缓存：uid → 系统里的名字（`cmd user list --all -v` 的 name= 字段） */
    static volatile Map<Integer, String> SPACE_NAMES = new HashMap<Integer, String>();
    /** 我们建的空间按扫描顺序（只用于显示「空间 1/2/3」，跟系统 id 无关） */
    static volatile java.util.List<Integer> SPACE_ORDER = new ArrayList<Integer>();

    /**
     * 空间显示名：我们自己起的 → 系统里的名字 → 「空间 <uid>」。
     *
     * 系统名只能建空间时给（`setUserName` 要 MANAGE_USERS，shell 身份必被拒，
     * 所以「重命名」改的是本 App 的标签，不再假装能改系统名）。
     */
    static String nameOf(Context c, int uid) {
        // 空间名就是系统名（LSP 管理器里看到的那个），一套名字，不再另存「界面名」。
        String sys = SPACE_NAMES.get(Integer.valueOf(uid));
        if (sys == null || sys.trim().length() == 0) {
            sys = spaceName(uid);          // 外部空间（一加 999）不在缓存里，现查一次
        }
        if (sys != null && sys.trim().length() > 0) {
            String t2 = sys.trim();
            // 老空间的 WB_ 前缀只是历史归属标志，不在界面上露出来
            if (t2.startsWith(TAG)) t2 = t2.substring(TAG.length()).trim();
            if (t2.length() > 0 && !t2.equalsIgnoreCase("space")) return t2;
        }
        // 兜底（老空间 WB_space / 读不到系统名）：按建号顺序显示「空间 N」
        int ord = SPACE_ORDER.indexOf(Integer.valueOf(uid));
        if (ord >= 0) return c.getString(R.string.space_name, String.valueOf(ord + 1));
        return c.getString(R.string.space_name, String.valueOf(uid));
    }


    /**
     * 分身空间 uid 列表。
     * `cmd user list --all -v` 约 0.2s；以前用 app_process 起 JVM 要 2.1s，每次刷新都白等。
     */
    static java.util.List<Integer> listSpaceUids() {
        java.util.List<Integer> ids = new ArrayList<Integer>();
        Map<Integer, String> names = new HashMap<Integer, String>();
        Set<Integer> ghosts = new HashSet<Integer>();
        // 顺带取一次「卡在 <removing> 的幽灵空间」：这类档案 pm remove-user 早返回 Success 了，
        // 但状态停在 STOPPING，`am stop-user` 也会被拒（-4 related users can't be stopped），
        // 只有重启能清 —— 界面上不能再把它当成活着的空间。
        String out = Shell.exec("cmd user list --all -v 2>/dev/null; echo '#RM#'; "
                + "dumpsys user 2>/dev/null | grep '<removing>'");
        boolean rm = false;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#RM#")) {
                rm = true;
                continue;
            }
            if (rm) {
                int a = t.indexOf('{'), b = a < 0 ? -1 : t.indexOf(':', a);
                if (a >= 0 && b > a) {
                    try {
                        ghosts.add(Integer.valueOf(t.substring(a + 1, b)));
                    } catch (Throwable ignored) {
                    }
                }
                continue;
            }
            if (!t.contains("type=profile.CLONE")) continue;
            int i = t.indexOf("id=");
            if (i < 0) continue;
            int j = i + 3, k = j;
            while (k < t.length() && Character.isDigit(t.charAt(k))) k++;
            try {
                int uid = Integer.parseInt(t.substring(j, k));
                int n = t.indexOf("name=");
                int e = n < 0 ? -1 : t.indexOf(',', n);
                String nm = e > n ? t.substring(n + 5, e).trim() : "";
                if (!Launcher.safe(uid)) continue;
                // 名字无条件记下来：isOurs(null, uid) 的调用方（删分身、回收空间）
                // 要靠它认出「名字带 WB_」的空间，不然会把我们自己的空间判成只读
                if (nm.length() > 0) names.put(Integer.valueOf(uid), nm);
                // 归属只看「名字里的 WB_ 前缀」或「空间内 wb_clone 标记」，
                // 不再用 flags 猜 —— flags 是系统的实现细节，ROM 自带分身也长一个样。
                if (isOurs(nm, uid)) {
                    ids.add(Integer.valueOf(uid));
                }
            } catch (Throwable ignored) {
            }
        }
        ids.removeAll(ghosts);
        SPACE_ORDER = new ArrayList<Integer>(ids);
        SPACE_NAMES = names;
        return ids;
    }

    /** 建空间失败：是到上限就说上限，别拿英文报错糊用户 */
    private String failMsg(String err) {
        String e = err == null ? "" : err;
        boolean limit = e.contains("Cannot add more") || e.contains("returned null")
                || e.contains("maximum") || e.contains("MAX");
        if (!limit) return getString(R.string.create_failed, e);
        int per = maxPerParent();
        return per > 0 ? getString(R.string.space_full, String.valueOf(per))
                : getString(R.string.space_limit_one);
    }

    /** 本机允许的 clone profile 上限（同一个父用户下） */
    private int maxPerParent() {
        String out = Shell.exec("dumpsys user");
        int i = out.indexOf("android.os.usertype.profile.CLONE:");
        if (i < 0) return -1;
        String sub = out.substring(i, Math.min(out.length(), i + 600));
        for (String line : sub.split("\n")) {
            String t = line.trim();
            if (t.startsWith("mMaxAllowedPerParent:")) {
                try {
                    return Integer.parseInt(t.substring(t.indexOf(':') + 1).trim());
                } catch (Throwable ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 找一个可以装下该应用的分身空间。
     * 规则：空间里不能已经有这个应用；优先挑应用最少的那个。
     * 找不到返回 -1。
     */
    static int pickReusableSpace(Context c, String pkg) {
        java.util.List<Integer> l = reusableSpaces(c, pkg);
        return l.isEmpty() ? -1 : l.get(0).intValue();
    }

    /**
     * 能装这个应用的空间（我们自己建的、且还没有它），按已有分身数升序。
     * ⚠判断「空间里已经有它」只能用登记表：新建空间时系统会把主身份的全部包灌进来
     * （红米 180 个、一加 378 个），按包列表算的话每个空间都「已经有它」，永远不肯复用。
     */
    static java.util.List<Integer> reusableSpaces(Context c, String pkg) {
        final Map<Integer, Integer> n = new HashMap<Integer, Integer>();
        java.util.List<Integer> out = new ArrayList<Integer>();
        for (int uid : listSpaceUids()) {
            Set<String> kept = keptPkgs(c, uid);
            if (kept != null && kept.contains(pkg)) continue;
            n.put(Integer.valueOf(uid), Integer.valueOf(kept == null ? 0 : kept.size()));
            out.add(Integer.valueOf(uid));
        }
        Collections.sort(out, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int d = n.get(a).intValue() - n.get(b).intValue();
                return d != 0 ? d : a.intValue() - b.intValue();
            }
        });
        return out;
    }

    /** 打开主身份里那份原应用（当前进程就是主用户，直接 startActivity） */
    private void openPrimary(Group g) {
        Intent it = getPackageManager().getLaunchIntentForPackage(g.pkg);
        if (it == null) {
            toast(getString(R.string.launch_failed, g.label));
            return;
        }
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        toast(getString(R.string.opening_primary, g.label));
        try {
            startActivity(it);
        } catch (Throwable t) {
            toast(getString(R.string.launch_failed, g.label));
        }
    }

    /** 分身行长按菜单：隐藏的可以一键启用，也能直接移除 */
    private void cloneMenu(final Clone c, final Group g) {
        final java.util.List<String> items = new java.util.ArrayList<String>();
        final java.util.List<Integer> acts = new java.util.ArrayList<Integer>();
        boolean ro = c.foreign && !sp().getBoolean("manage_foreign", false);
        if (c.off && !ro) { items.add(getString(R.string.menu_enable)); acts.add(1); }
        items.add(getString(R.string.menu_open)); acts.add(2);
        if (!ro) { items.add(getString(R.string.menu_remove)); acts.add(3); }
        new android.app.AlertDialog.Builder(this)
                .setTitle(g.label + suffixOf(c.uid, g.pkg, 0))
                .setItems(items.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                int a = acts.get(which).intValue();
                                if (a == 1) enableClone(c, g);
                                else if (a == 2) launch(c, g);
                                else confirmRemove(c, g);
                            }
                        })
                .show();
    }

    /** 把被隐藏的分身重新启用（pm enable，数据原样还在） */
    private void enableClone(final Clone c, final Group g) {
        toast(getString(R.string.opening, g.label));
        new Thread(new Runnable() {
            public void run() {
                final String r = Launcher.enable(c.uid, g.pkg);
                runOnUiThread(new Runnable() {
                    public void run() {
                        boolean ok = r.toLowerCase().contains("new state");
                        toast(ok ? getString(R.string.clone_enabled, g.label) : r.trim());
                        dirty = true;
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void launch(final Clone c, final Group g) {
        toast(getString(R.string.opening, g.label));
        new Thread(new Runnable() {
            public void run() {
                if (!Launcher.launch(MainActivity.this, c.uid, g.pkg)) {
                    runOnUiThread(new Runnable() {
                        public void run() { toast(getString(R.string.launch_failed, g.label)); }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        recordRecent(c.uid, g.pkg);
                        onRecentChanged();
                    }
                });
            }
        }).start();
    }

    // ---------------- 网格顶部「最近打开」 ----------------

    private static final int RECENT_MAX = 6;

    /** 每条 "uid:pkg"，最新在前，去重限量 */
    private List<String> loadRecent() {
        List<String> list = new ArrayList<String>();
        String raw = sp().getString("recent_clones", "");
        for (String s : raw.split(";")) {
            if (s.contains(":")) list.add(s);
        }
        return list;
    }

    private void recordRecent(int uid, String pkg) {
        String key = uid + ":" + pkg;
        List<String> list = loadRecent();
        list.remove(key);
        list.add(0, key);
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append(';');
        sp().edit().putString("recent_clones", sb.toString()).apply();
    }

    /** 动态创建「最近打开」区：标题 + 分行小图标 + 分隔线（不再依赖 XML include） */
    private void createRecentHeader() {
        recentHeader = new LinearLayout(this);
        recentHeader.setOrientation(LinearLayout.VERTICAL);
        recentTitle = new TextView(this);
        recentTitle.setText(R.string.recent_opened);
        recentTitle.setTextSize(13);
        recentRow = new LinearLayout(this);
        recentRow.setOrientation(LinearLayout.VERTICAL);
        recentDivider = new View(this);
        recentDivider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        recentHeader.addView(recentTitle);
        recentHeader.addView(recentRow);
        recentHeader.addView(recentDivider);
    }

    /** 刷「最近打开」区内容；返回是否有可显示的项（挂到哪由 render/buildGrid 决定） */
    private boolean refreshRecentHeader() {
        if (recentHeader == null) return false;
        recentRow.removeAllViews();
        boolean dark = isNight();
        int nameColor = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        recentTitle.setTextColor(dark ? 0xFF9AA0A6 : 0xFF5F6368);
        recentDivider.setBackgroundColor(dark ? 0xFF2C2D31 : 0xFFE3E3E8);

        // 先收集仍存在的分身：反查 allClones，不分用户/系统页签（最近打开要两者都含）
        List<Clone> hits = new ArrayList<Clone>();
        List<String> pkgs = new ArrayList<String>();
        for (String entry : loadRecent()) {
            int sep = entry.indexOf(':');
            final int uid;
            final String pkg;
            try {
                uid = Integer.parseInt(entry.substring(0, sep));
                pkg = entry.substring(sep + 1);
            } catch (Throwable t) { continue; }
            Clone c = allClones.get(uid + ":" + pkg);
            if (c == null) continue;   // 分身已被删 / 不在本次扫描里
            hits.add(c);
            pkgs.add(pkg);
        }
        if (hits.isEmpty()) return false;

        // 行数上限：只画最近 cols*rows 个，超出的丢掉
        int cols = Math.max(1, recentCols());
        int max = cols * recentRows();
        while (hits.size() > max) {
            hits.remove(hits.size() - 1);
            pkgs.remove(pkgs.size() - 1);
        }

        // 边距跟所在视图对齐：列表卡片 16dp、网格格子 8dp
        int pad = gridMode() ? dp(8) : dp(16);
        recentTitle.setPadding(pad, dp(8), pad, 0);
        recentRow.setPadding(pad, dp(4), pad, dp(6));

        LinearLayout line = null;
        for (int i = 0; i < hits.size(); i++) {
            if (i % cols == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                recentRow.addView(line);
            }
            final Clone c = hits.get(i);
            final String pkg = pkgs.get(i);
            final int uid = c.uid;
            String label = labelCache.get(pkg);
            if (label == null) label = pkg;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(dp(64),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            item.setPadding(dp(6), dp(4), dp(6), 0);
            ImageView iv = new ImageView(this);
            Drawable ic = iconCache.get(pkg);
            if (ic != null) iv.setImageBitmap(badge(ic, cloneIndex(pkg, uid)));
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
            if (c.off) iv.setAlpha(0.4f);
            item.addView(iv);
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextSize(11);
            tv.setTextColor(c.off ? (dark ? 0xFF7A7A7A : 0xFFA0A0A0) : nameColor);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setMaxWidth(dp(64));
            item.addView(tv);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { onRecentClick(uid, pkg, c); }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    Group g = findGroup(pkg);
                    if (g != null) cloneMenu(c, g);
                    return true;
                }
            });
            line.addView(item);
        }
        return true;
    }

    /** 这个包在这个 uid 下的分身序号（按 uid 升序），用来和列表/网格的角标颜色对齐 */
    private int cloneIndex(String pkg, int uid) {
        java.util.List<Integer> uids = new java.util.ArrayList<Integer>();
        for (java.util.Map.Entry<String, Clone> e : allClones.entrySet()) {
            String k = e.getKey();
            int sep = k.indexOf(':');
            if (sep < 0) continue;
            if (!k.substring(sep + 1).equals(pkg)) continue;
            uids.add(Integer.valueOf(e.getValue().uid));
        }
        java.util.Collections.sort(uids);
        int i = uids.indexOf(Integer.valueOf(uid));
        return i < 0 ? 0 : i;
    }

    /** 最近记录变了：重刷内容；挂载状态变了（该挂没挂/该摘没摘）就整体重排一次 */
    private void onRecentChanged() {
        boolean has = refreshRecentHeader();
        if (has != (recentHeader.getParent() != null)) render();
    }

    /** 最近打开区点一下：直接拉起对应分身（当前页签里找不到 Group 就用缓存现拼一个） */
    private void onRecentClick(int uid, String pkg, Clone c) {
        if (c.off) { toast(getString(R.string.clone_hidden_hint)); return; }
        Group g = findGroup(pkg);
        if (g == null) {
            g = new Group();
            g.pkg = pkg;
            g.label = labelCache.get(pkg);
            if (g.label == null) g.label = pkg;
            g.icon = iconCache.get(pkg);
        }
        launch(c, g);
    }

    private Group findGroup(String pkg) {
        for (Group g : groups) if (g.pkg.equals(pkg)) return g;
        return null;
    }

    // ---------------- 分身后缀（改名）----------------

    private android.content.SharedPreferences sp() {
        return getSharedPreferences("clone", MODE_PRIVATE);
    }

    /** 创建分身时是否自动往桌面加图标 */
    /** 设置里「分身里保留系统应用」：开了就不做清理 */
    private boolean keepSys() {
        return sp().getBoolean("keep_sys", false);
    }

    private boolean autoIcon() {
        return sp().getBoolean("auto_icon", false);
    }

    /** 分身显示的后缀，默认 1、2、3，改过就用改过的 */
    private String suffixOf(int uid, String pkg, int index) {
        String v = sp().getString("sfx_" + uid + "_" + pkg, null);
        return (v != null && v.length() > 0) ? v : String.valueOf(index + 1);
    }

    private void askRename(final Clone c, Group g, int idx) {
        final String pkg = g.pkg;
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(suffixOf(c.uid, pkg, idx));
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_title)
                .setView(et)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        sp().edit().putString("sfx_" + c.uid + "_" + pkg, n).apply();
                        render();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(final Clone c, final Group g) {
        if (!Launcher.safe(c.uid)) {
            say(getString(R.string.not_clone, c.uid));
            return;
        }
        // 不是我们建的空间（一加 MultiApp 之类）：默认只读，绝不从这里卸人家的应用；
        // 用户开了「管理非自建空间」才放开
        if (!isOurs(null, c.uid) && !sp().getBoolean("manage_foreign", false)) {
            say(getString(R.string.space_readonly));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_clone_title)
                .setMessage(getString(R.string.remove_clone_msg, g.label))
                .setPositiveButton(R.string.delete_clone_btn, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                // 只卸应用，绝不在这里删用户 —— 删用户只在设置页做
                                Launcher.uninstall(c.uid, g.pkg);
                                unmarkKept(MainActivity.this, c.uid, g.pkg);
                                // 「空间空了」要按删完之后实际还剩什么判断。
                                // ⚠老空间没有登记表，keptPkgs 返回 null；以前把 null 当空 ⇒
                                // 删掉一个分身就弹「要不要删空间」，点下去整个空间都没了。
                                Set<String> left = keptPkgs(MainActivity.this, c.uid);
                                final boolean spaceEmpty;
                                if (left != null && !left.isEmpty()) {
                                    spaceEmpty = false;            // 登记表还有，肯定不算空
                                } else {
                                    spaceEmpty = iconsInSpace(c.uid) == 0;   // -1（判不了）时不提示
                                }
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        unpinShortcut(MainActivity.this, c.uid, g.pkg);
                                        say(spaceEmpty
                                                ? getString(R.string.removed_clone_empty, g.label)
                                                : getString(R.string.removed_clone, g.label));
                                        refresh();
                                        if (spaceEmpty) askRecycleSpace(c.uid);
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 空间空了，问要不要回收 —— 回收后名额可以重新用 */
    private void askRecycleSpace(final int uid) {
        // 只读空间（别人建的）永远不给「删空间」这个入口，哪怕上层判断漏了也要挡住
        if (!isOurs(null, uid)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.recycle_title)
                .setMessage(getString(R.string.recycle_msg))
                .setPositiveButton(R.string.recycle_btn, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.exec(WB + " remove " + uid);
                                if (!listSpaceUids().contains(Integer.valueOf(uid))) {
                                    // 空间没了，登记表 / 归属登记一起删
                                    getSharedPreferences("clone", MODE_PRIVATE)
                                            .edit().remove("space_pkgs_" + uid).apply();
                                    unmarkOurs(uid);
                                }
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        unpinShortcut(MainActivity.this, uid, null);
                                        say(getString(R.string.recycled, uid));
                                        refresh();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(R.string.keep_btn, null)
                .show();
    }

    /** 删分身时把桌面图标一起撤掉；pkg 传 null 表示撤掉这个空间下的所有图标 */
    /**
     * 桌面自愈：凡是 clone_<uid>_* 的图标，uid 已经不是一个活着的分身空间，
     * 就把图标撤掉（空间删了图标还赖在桌面上就是因为以前没做这步）。
     */
    static void cleanStaleShortcuts(Context a) {
        try {
            ShortcutManager sm = (ShortcutManager) a.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) return;
            java.util.List<Integer> alive = listSpaceUids();
            List<String> dead = new ArrayList<String>();
            for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                String id = si.getId();
                if (id == null || !id.startsWith("clone_")) continue;
                String[] parts = id.split("_");
                if (parts.length < 2) continue;
                try {
                    int uid = Integer.parseInt(parts[1]);
                    if (!alive.contains(Integer.valueOf(uid))) dead.add(id);
                } catch (Throwable ignored) {
                }
            }
            if (dead.isEmpty()) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    sm.removeLongLivedShortcuts(dead);
                } catch (Throwable ignored) {
                }
            }
            try {
                sm.disableShortcuts(dead);
            } catch (Throwable ignored) {
            }
            android.util.Log.i("CubClone", "cleaned stale shortcuts " + dead);
        } catch (Throwable ignored) {
        }
    }

    static void unpinShortcut(Context a, int uid, String pkg) {
        try {
            ShortcutManager sm = (ShortcutManager) a.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) return;
            String prefix = "clone_" + uid + "_";

            List<String> pinned = new ArrayList<String>();
            for (ShortcutInfo si : sm.getPinnedShortcuts()) {
                String id = si.getId();
                if (id == null) continue;
                if (id.startsWith(prefix) && (pkg == null || id.equals(prefix + pkg))) {
                    pinned.add(id);
                }
            }
            if (!pinned.isEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    sm.removeLongLivedShortcuts(pinned);
                } catch (Throwable t) {
                    android.util.Log.w("CubClone", "unpin removeLongLived: " + t);
                }
            }

            List<String> dyn = new ArrayList<String>();
            for (ShortcutInfo si : sm.getDynamicShortcuts()) {
                String id = si.getId();
                if (id == null) continue;
                if (id.startsWith(prefix) && (pkg == null || id.equals(prefix + pkg))) dyn.add(id);
            }
            if (!dyn.isEmpty()) {
                try {
                    sm.disableShortcuts(dyn);
                } catch (Throwable ignored) {
                }
                try {
                    sm.removeDynamicShortcuts(dyn);
                } catch (Throwable ignored) {
                }
            }
            android.util.Log.i("CubClone", "unpin " + prefix + " pkg=" + pkg
                    + " pinned=" + pinned.size() + " dyn=" + dyn.size());
        } catch (Throwable ignored) {
        }
    }

    /** 给某个分身里的应用钉一个桌面图标 */
    static void pinShortcut(Activity a, int uid, String pkg, String label, Drawable icon,
                            String profileName) {
        try {
            ShortcutManager sm = (ShortcutManager) a.getSystemService(Context.SHORTCUT_SERVICE);
            if (sm == null) {
                Toast.makeText(a, a.getString(R.string.pin_failed, "no shortcut service"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String id = "clone_" + uid + "_" + pkg;
            Intent it = new Intent(a, LaunchProxyActivity.class);
            it.setAction(Intent.ACTION_VIEW);
            it.putExtra("uid", uid);
            it.putExtra("pkg", pkg);

            ShortcutInfo.Builder b = new ShortcutInfo.Builder(a, id)
                    .setShortLabel(label)
                    .setLongLabel(label + "（" + profileName + "）")
                    .setIntent(it);
            if (android.os.Build.VERSION.SDK_INT >= 30) b.setLongLived(true);
            // 图标按桌面自己报的最大尺寸画（Island 也是这么干的）：画布 = (1+2*extraInset) × 最大尺寸，
            // 内容画在中间安全区、铺白底，桌面再套 mask 不会二次留白——和真图标一样大。
            Drawable src = icon != null ? icon : appIconDrawable(a, pkg);
            Bitmap bm = adaptiveIcon(src, sm.getIconMaxWidth(), sm.getIconMaxHeight());
            if (bm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    b.setIcon(Icon.createWithAdaptiveBitmap(bm));
                } else {
                    b.setIcon(Icon.createWithBitmap(bm));
                }
            }
            ShortcutInfo si = b.build();

            // ① 先登记成「动态快捷方式」：桌面处理 PinItemRequest 时要能在 ShortcutService 里
            //    查到这条记录，查不到就报 "Unable to find app for caller (pid=-1)"、弹窗一闪就没。
            //    Island 同样是 addDynamicShortcuts 之后才 requestPinShortcut。
            //    顺带兜底：不支持钉图标的桌面，长按本 App 图标也能把它拖到桌面。
            try {
                sm.addDynamicShortcuts(java.util.Collections.singletonList(si));
            } catch (Throwable t) {
                android.util.Log.w("CubClone", "addDynamicShortcuts: " + t);
            }
            // ② 桌面不支持就明说，别再静默返回（之前点了完全没反应就是这个）
            if (!sm.isRequestPinShortcutSupported()) {
                Toast.makeText(a, a.getString(R.string.pin_unsupported), Toast.LENGTH_LONG).show();
                return;
            }
            sm.requestPinShortcut(si, null);
        } catch (Throwable t) {
            android.util.Log.w("CubClone", "pin failed: " + t);
            Toast.makeText(a, a.getString(R.string.pin_failed, String.valueOf(t)),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 画到桌面要的规格：画布放大 1+2*inset，内容居中，白底防透明区发黑 */
    static Bitmap adaptiveIcon(Drawable d, int w, int h) {
        if (d == null || w <= 0 || h <= 0) return null;
        try {
            float inset = 0f;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                inset = android.graphics.drawable.AdaptiveIconDrawable.getExtraInsetFraction();
            }
            Bitmap bm = Bitmap.createBitmap(Math.round((1 + 2 * inset) * w),
                    Math.round((1 + 2 * inset) * h), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            c.drawColor(0xFFFFFFFF);
            d.setBounds(Math.round(w * inset), Math.round(h * inset),
                    Math.round(w * (1 + inset)), Math.round(h * (1 + inset)));
            d.draw(c);
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    static Drawable appIconDrawable(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    static Icon appIcon(Context c, String pkg) {
        try {
            int rid = c.getPackageManager().getApplicationInfo(pkg, 0).icon;
            if (rid != 0) return Icon.createWithResource(pkg, rid);
        } catch (Throwable ignored) {
        }
        return null;
    }

    static Bitmap toBitmap(Drawable d) {
        if (d == null) return null;
        int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96;
        int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96;
        if (w > 192) w = 192;
        if (h > 192) h = 192;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return bm;
    }

    // ---------------- 底层 ----------------

    private boolean deploy() {
        if (sp().getInt("deployed", -1) == buildVersion()) return true;
        try {
            InputStream is = getAssets().open(JAR);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String r = Shell.write(REMOTE, bos.toByteArray());
            boolean ok = !r.startsWith("ERR") && !r.contains("denied")
                    && !r.contains("Permission denied");
            if (ok) sp().edit().putInt("deployed", buildVersion()).apply();
            return ok;
        } catch (Throwable t) {
            android.util.Log.e("CubClone", "deploy fail", t);
            return false;
        }
    }

    private int buildVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 状态行默认内容＝权限就绪状态；临时提示过几秒自动回落，不会一直挂着上一条 */
    private String idleText() {
        if (Shell.rootOk) return getString(R.string.ready_root);
        if (Shell.shizukuOk) return getString(R.string.ready_shizuku);
        return getString(R.string.st_no_root);
    }

    /** 页面底部已经有状态行，常驻类提示走这里（打开应用用 toast） */
    private void say(String s) {
        if (status == null) return;
        status.setText(s);
        ui.removeCallbacks(idle);
        ui.postDelayed(idle, 4000);
    }

    private final Runnable idle = new Runnable() {
        public void run() {
            if (status != null) status.setText(idleText());
        }
    };

    /** 状态行落回默认值（页面重进、权限就绪时调用） */
    private void settle() {
        ui.removeCallbacks(idle);
        if (status != null) status.setText(idleText());
    }

    /** 只在页面会被挡住（如系统加图标弹窗）时才用 toast */
    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
