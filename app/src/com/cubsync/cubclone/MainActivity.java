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
import android.graphics.Typeface;
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
 * 应用分身主界面 —— 一个应用一张卡片：
 * 每个应用一张卡片，第一行是主身份那份 + 「创建分身」按钮，
 * 下面每行是一个分身（图标带角标、名字带序号、右侧改名/删除）。
 */
public class MainActivity extends Activity {

    private static final String JAR = "cubclone.jar";
    private static final String REMOTE = "/data/local/tmp/cubclone.jar";
    private static final String CLI = "CLASSPATH=" + REMOTE + " app_process /system/bin CubClone";
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
    private LinearLayout recentHostList, recentHostGrid;
    private LinearLayout listOuter, gridOuter;
    private LinearLayout tabRow;
    /** 网格格子高度：布局就绪后统一算 */
    private int gridItemH;
    /** 「最近打开」区（动态创建，挂进列表/网格滚动内容顶部，随滚动收起） */
    private LinearLayout recentHeader;
    private TextView recentTitle;
    private LinearLayout recentRow;
    private View searchRow;
    /** 所有有效分身（不分用户/系统页签）：key="uid:pkg"，供「最近打开」反查图标/名字/禁用态 */
    private final Map<String, Clone> allClones = new HashMap<String, Clone>();

    private final List<Group> groups = new ArrayList<Group>();

    /** 主界面多选：长按应用进入多选，勾选多个应用批量创建分身 */
    private boolean multi = false;
    /** 网格模式下长按主应用进多选会临时切到列表，退出时按这个标记恢复网格 */
    private boolean restoreGrid = false;
    private final Set<String> selected = new HashSet<String>();
    private View multiBar;
    private TextView multiCount;
    private android.widget.Button btnSelectAll, btnInvert, btnAdd, btnRemove;

    /** 图标缓存：LruCache 按像素内存上限 24MB，防几百个图标无上限撑爆内存（启动器同款做法） */
    private final android.util.LruCache<String, Drawable> iconCache =
            new android.util.LruCache<String, Drawable>(24 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Drawable value) {
                    int w = value.getIntrinsicWidth(), h = value.getIntrinsicHeight();
                    return (w > 0 && h > 0) ? w * h * 4 : 44 * 44 * 4;   // ARGB_8888 每像素 4 字节
                }
            };
    private final Map<String, String> labelCache = new HashMap<String, String>();
    /** 最终 44dp 位图缓存（含角标合成结果）：render 全量重建时直接 setImageBitmap，不再每帧 Canvas 重画 */
    private final android.util.LruCache<String, Bitmap> iconBitmapCache =
            new android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    /** 图标异步懒加载：单线程加载器，任务内部先查缓存（启动器式：首屏不阻塞，图标后台渐显） */
    private final java.util.concurrent.ExecutorService iconLoader =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 只认第三方应用：分身用户里天然带着一堆系统应用 */
    private final Set<String> userApps = new HashSet<String>();
    private final Object metaLock = new Object();

    private EditText search;
    private android.widget.Switch scopeSwitch;

    /** 0 = 用户应用，1 = 系统应用 */
    private int tab = 0;
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

    /** 主身份应用装/卸/替换时置 metaDirty，应用清单下次刷新时重读（替代刷新按钮暴力重载） */
    private final android.content.BroadcastReceiver pkgReceiver =
            new android.content.BroadcastReceiver() {
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    int uid = i.getIntExtra(android.content.Intent.EXTRA_UID, -1);
                    if (uid >= 10000) return;   // 只关心机主（user 0）；分身的 install-existing 不重载清单
                    CloneStore.metaDirty = true;
                }
            };

    /** 记下进页面时的语言，从设置页回来发现变了就重建（XML 文本才会换） */
    private String langAtCreate;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CloneStore.attach(this);
        // 备份目录是设置项，备份/克隆都要用，进页面先读一遍
        AppData.loadRoot(sp());
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

        initHeader();

        tab = sp().getInt("tab", 0);
        tabRow = (LinearLayout) LayoutInflater.from(this).inflate(R.layout.tab_row, listOuter, false);
        tabUserText = tabRow.findViewById(R.id.tab_user_text);
        tabSysText = tabRow.findViewById(R.id.tab_sys_text);
        tabUserLine = tabRow.findViewById(R.id.tab_user_line);
        tabSysLine = tabRow.findViewById(R.id.tab_sys_line);
        tabRow.findViewById(R.id.tab_user).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchTab(0); }
        });
        tabRow.findViewById(R.id.tab_sys).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { switchTab(1); }
        });
        listOuter.addView(tabRow, 1);   // 挂滚动内容顶部（recent_host 之后、列表之前），随滑动滚走/回顶

        Shizuku.addRequestPermissionResultListener(onShizukuResult);

        new Thread(new Runnable() {
            public void run() {
                Shell.checkRoot();
                loadAppMetaQuick();   // 分类 + 真实名字（图标走异步懒加载，首屏不阻塞）
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (Shell.rootOk) {
                            startWork();
                        }
                        else {
                            say(getString(R.string.st_no_root));
                            initShizuku();
                        }
                    }
                });
            }
        }).start();

        // 监听主身份应用装/卸/替换：变了才重载应用清单（增量），刷新按钮不再全量重载
        android.content.IntentFilter pf = new android.content.IntentFilter();
        pf.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
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
        if (CloneStore.dirty || groups.isEmpty()) {
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
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) { }
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
        listOuter = findViewById(R.id.list_outer);
        gridOuter = findViewById(R.id.grid_outer);
        recentHostList = findViewById(R.id.recent_host_list);
        recentHostGrid = findViewById(R.id.recent_host_grid);
        // 「最近打开」区：动态创建，挂到各视图的 recent_host（无 padding），缩进自己管，和列表/网格解耦
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
        btnRemove = findViewById(R.id.btn_remove);
        btnRemove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (selected.isEmpty()) {
                    toast(getString(R.string.batch_none_selected));
                    return;
                }
                Intent it = new Intent(MainActivity.this, RemoveClonesActivity.class);
                it.putExtra(RemoveClonesActivity.EXTRA_PKGS, selected.toArray(new String[selected.size()]));
                startActivity(it);
            }
        });
        btnSelectAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                selected.clear();
                for (Group g : groups) {
                    boolean isUser = userApps.contains(g.pkg) || !sysApps.contains(g.pkg);
                    if ((tab == 0) == isUser) selected.add(g.pkg);   // 只选当前页签
                }
                render();
            }
        });
        btnInvert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Set<String> inv = new HashSet<String>();
                for (Group g : groups) {
                    boolean isUser = userApps.contains(g.pkg) || !sysApps.contains(g.pkg);
                    if ((tab == 0) == isUser && !selected.contains(g.pkg)) inv.add(g.pkg);
                }
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

    /** 退出多选（批量创建）：清状态、藏工具条，若进多选时是从网格切来的就恢复网格 */
    private void exitMulti() {
        multi = false;
        selected.clear();
        multiBar.setVisibility(View.GONE);
        if (restoreGrid) {
            restoreGrid = false;
            setGridMode(true);
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
            exitMulti();
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
        // 必须重新扫描、不能只 render()：show_all 同时影响 scan 层的「禁用过滤」
        // （showDisabled 里 || show_all）和渲染层的「无分身过滤」，只 render 的话
        // 禁用包在扫描时已经被丢掉了，切到「显示全部」也回不来（川：要手动点刷新才全）。
        refresh();
    }

    /** 左右滑动切页签：跟手位移（内容跟着手指动），松手过半/够远/快速甩才切，否则回弹 */
    private float swipeX0, swipeY0, swipeLastX;
    private long swipeT0, swipeLastMoveT;
    private View swipeTarget;
    private boolean swipingH;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        final int a = ev.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            swipeX0 = ev.getX();
            swipeY0 = ev.getY();
            swipeLastX = ev.getX();
            swipeT0 = swipeLastMoveT = System.currentTimeMillis();
            swipingH = false;
            swipeTarget = gridMode() ? gridBox : listBox;   // 只平移列表/网格内容，最近打开+tab 行不动
        } else if (a == android.view.MotionEvent.ACTION_MOVE && swipingH) {
            if (swipeTarget != null) swipeTarget.setTranslationX(ev.getX() - swipeX0);   // 跟手
            swipeLastX = ev.getX();
            swipeLastMoveT = System.currentTimeMillis();
            return true;
        } else if (a == android.view.MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - swipeX0, dy = ev.getY() - swipeY0;
            // 用系统 touch slop 判「横向意图」，比固定 24dp 更早更准，且横向为主（防斜滑误触点击）
            int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)
                    && ((dx > 0 && tab == 1) || (dx < 0 && tab == 0))) {
                swipingH = true;
                swipeTarget = gridMode() ? gridBox : listBox;
                if (swipeTarget != null) swipeTarget.setTranslationX(dx);
                return true;
            }
        } else if (a == android.view.MotionEvent.ACTION_UP
                || a == android.view.MotionEvent.ACTION_CANCEL) {
            if (swipingH) {
                swipingH = false;
                final View t = swipeTarget;
                float dx = ev.getX() - swipeX0;
                long dt = System.currentTimeMillis() - swipeLastMoveT;
                float vx = dt > 0 ? (ev.getX() - swipeLastX) * 1000f / dt : 0f;   // px/s
                // 切换：位移过半，或位移够 60dp，或快速甩（fling）
                boolean go = (t != null && Math.abs(dx) > t.getWidth() / 2f)
                        || Math.abs(dx) > dp(60)
                        || Math.abs(vx) > 800f;
                int want = dx < 0 ? 1 : 0;
                if (go && want != tab) {
                    switchTab(want);                      // 内容重渲染后归位
                    if (t != null) t.setTranslationX(0);
                } else if (t != null) {
                    t.animate().translationX(0).setDuration(180).start();   // 回弹
                }
                return true;
            }
            long ms = System.currentTimeMillis() - swipeT0;
            float dx = ev.getX() - swipeX0, dy = ev.getY() - swipeY0;
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
    /** 换页签：groups 已含两个页签全量，只 render 切换显示、不重扫（秒切） */
    private void switchTab(int which) {
        if (tab == which) return;
        tab = which;
        sp().edit().putInt("tab", which).apply();
        applyTabs();
        if (groups.isEmpty()) refresh();   // 还没扫过就重扫一次
        else render();                      // 已扫过，直接切显示
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

    /** 分类（userApps/sysApps/THIRD）+ 真实名字；图标留空、走异步懒加载。首屏先出且名字一次正确。 */
    private void loadAppMetaQuick() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        if (ris == null || ris.isEmpty()) return;
        iconCache.evictAll();
        labelCache.clear();
        userApps.clear();
        CloneStore.THIRD = new HashSet<String>();
        sysApps.clear();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            labelCache.put(pkg, String.valueOf(ri.loadLabel(pm)));   // 真名，别用包名占位（否则首屏闪包名）
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
                    userApps.add(pkg);
                    CloneStore.THIRD.add(pkg);
                } else {
                    sysApps.add(pkg);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 数据 ----------------

    /** 顶栏刷新按钮：重扫空间状态；主身份应用清单靠包变更广播增量更新，不在这里暴力重载 */
    private void refreshNow() {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say(getString(R.string.refreshing));
        CloneStore.dirty = true;
        refresh();
    }

    private volatile boolean refreshing;
    /** 上一次 render 用的深浅色，用来判断 onResume 要不要重建列表 */
    private boolean lastDark, lastBlack;

    private void refresh() {
        if (!Shell.ready()) {        // 权限还没探完，等探完再刷
            CloneStore.dirty = true;
            return;
        }
        // 正在刷时来的请求不能丢：标记脏，当前这轮结束时（见下面的 settle）会自己再跑一轮。
        // 以前直接 return ⇒ 装完应用调 refresh() 正好撞上后台刷新，界面就停在旧数据，
        // 看着像「没装进去」。
        if (refreshing) { CloneStore.dirty = true; return; }
        refreshing = true;
        CloneStore.dirty = false;
        new Thread(new Runnable() {
            public void run() {
                // 先读一遍空间归属标记（空间归属标记）：不读就等于把所有空间当外人
                CloneStore.ensureMarks();
                // onResume 可能比 onCreate 的后台加载跑得还早，没元信息就什么都匹配不上
                if (CloneStore.metaDirty || userApps.isEmpty() || labelCache.isEmpty()) {
                    CloneStore.metaDirty = false;
                    loadAppMetaQuick();   // 只分类+真实名字，图标走异步懒加载
                }
                // 全量扫描（用户+系统两个页签都含），切 tab 只 render 不重扫；tab 过滤放 render 层
                final Set<String> scope = new HashSet<String>(userApps);
                scope.addAll(sysApps);
                String out = Shell.exec(CloneStore.listBatch(MainActivity.this));
                Set<Integer> alive = new LinkedHashSet<Integer>();
                for (String line : out.split("\\n")) {
                    String t = line.trim();
                    if (!t.startsWith("SPACE ")) continue;
                    try { alive.add(Integer.valueOf(t.substring(6).trim())); }
                    catch (Throwable ignored) { }
                }

                Map<String, List<Clone>> map = new HashMap<String, List<Clone>>();
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
                        Clone c = new Clone();
                        c.uid = uid.intValue();
                        c.profile = "space";
                        c.foreign = true;
                        allClones.put(uid + ":" + pkg, c);
                        List<Clone> lo = map.get(pkg);
                        if (lo == null) { lo = new ArrayList<Clone>(); map.put(pkg, lo); }
                        lo.add(c);
                        continue;
                    }
                    // disabled 的包默认隐藏（多是建空间时被精简掉的系统应用，免得刷屏）；
                    // 开了「显示禁用」或「显示全部」才列出来（灰显），长按可启用。
                    if (off && !showDisabled()) continue;
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
                        settle();   // 刷新完成，状态行「正在刷新」立即回落，别等 4s 计时器
                        cleanStaleShortcuts(MainActivity.this);
                        refreshing = false;
                        if (CloneStore.dirty) refresh();
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

    /** 是否显示已禁用的应用：开了「显示禁用」或「显示全部」才显示 */
    private boolean showDisabled() {
        return sp().getBoolean("show_disabled", false) || sp().getBoolean("show_all", false);
    }

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
            // 页签过滤：用户页签显示 userApps 或「主身份认不出」的包，系统页签显示 sysApps
            boolean isUser = userApps.contains(g.pkg) || !sysApps.contains(g.pkg);
            if ((tab == 0) != isUser) continue;
            if (!showAll && g.clones.isEmpty()) continue;
            if (kw.length() > 0
                    && !g.label.toLowerCase().contains(kw)
                    && !g.pkg.toLowerCase().contains(kw)) {
                continue;
            }
            if (CloneStore.isSysHidden(g.pkg)) continue;   // 隐藏的主空间系统应用不显示
            if (showPrimary) flatItems.add(new FlatItem(g, null, -1));
            for (int i = 0; i < g.clones.size(); i++) {
                Clone cc = g.clones.get(i);
                if (CloneStore.isHidden(cc.uid, g.pkg)) continue;   // 隐藏的分身不显示（主应用仍在）
                flatItems.add(new FlatItem(g, cc, i));
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
        if (!gridMode()) return;   // 过期回调：已切回列表，别再动网格容器
        gridBox.removeAllViews();
        if (recentShow() && refreshRecentHeader()) {
            mountRecentHeader(recentHostGrid);   // 挂进滚动内容：上滑跟着滚走
        }
        mountTabRow(gridOuter);   // tab 行也挂进滚动内容顶部，随滑动滚走/回顶
        // 最近区刚挂上还没 measure，等一帧再算格子高度（computeItemH 要减它的高度）
        gridWrap.post(new Runnable() {
            public void run() {
                if (!gridMode()) return;
                buildCells();
            }
        });
    }

    private void buildCells() {
        if (!gridMode()) return;
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
            setIconAsync(icon, g.pkg, 0);
            name.setText(g.label);
            name.setTextColor(nameColor);
            icon.setAlpha(1f);
        } else {
            final Clone c = it.c;
            setIconAsync(icon, g.pkg, c.uid);
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

    /**
     * 网格长按：和列表**走同一套菜单**。
     * 以前网格长按应用图标直接进多选，列表却是弹菜单，同一个手势两种结果；
     * 多选现在是菜单里的第一项，两个视图都从那里进。
     */
    private void onGridLongClick(FlatItem it) {
        if (it.c == null) {
            baseMenu(it.g);
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
            mountRecentHeader(recentHostList);   // 挂进滚动内容：上滑跟着滚走
        }
        mountTabRow(listOuter);   // tab 行也挂进滚动内容顶部，随滑动滚走/回顶
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
            // 页签过滤：用户页签显示 userApps 或「主身份认不出」的包，系统页签显示 sysApps
            boolean isUser = userApps.contains(g.pkg) || !sysApps.contains(g.pkg);
            if ((tab == 0) != isUser) continue;
            // 默认只列已经开过分身的应用，避免一屏全是没动过的 App
            // 多选批量创建时列全部可分身应用（不只已开分身的），否则勾不到还没开过的应用
            if (!multi && !showAll && g.clones.isEmpty()) continue;
            if (kw.length() > 0
                    && !g.label.toLowerCase().contains(kw)
                    && !g.pkg.toLowerCase().contains(kw)) {
                continue;
            }
            if (CloneStore.isSysHidden(g.pkg)) continue;   // 隐藏的主空间系统应用不显示
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
            setIconAsync(bi, g.pkg, 0);
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
                // 长按：自己挑装到哪个空间（点＝自动挑最老的空闲空间）
                act.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { pickSpaceFor(g); return true; }
                });
                base.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { openPrimary(g); }
                });
                base.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { baseMenu(g); return true; }
                });
            }
            card.addView(base);

            // 下面：每个分身一行（多选批量创建时不显示分身行，只留主应用行）
            for (int i = 0; i < g.clones.size() && !multi; i++) {
                final Clone c = g.clones.get(i);
                if (CloneStore.isHidden(c.uid, g.pkg)) continue;   // 隐藏的分身不显示（主应用仍在）
                View row = inf.inflate(R.layout.item_clone_child, card, false);
                ripple(row);
                ImageView ci = row.findViewById(R.id.clone_icon);
                setIconAsync(ci, g.pkg, c.uid);
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
                                g.icon, CloneStore.nameOf(MainActivity.this, c.uid));
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
                final List<Integer> spaces = CloneStore.listSpaceUids();
                // 每个空间里 enabled 的三方应用数（和主页显示一致，不再用登记表猜）
                final java.util.Map<Integer, Integer> counts = new java.util.HashMap<Integer, Integer>();
                for (Integer u : spaces) {
                    int n = 0;
                    String out = Shell.exec("pm list packages -3 -e --user " + u.intValue());
                    for (String line : out.split("\n")) {
                        if (line.trim().startsWith("package:")) n++;
                    }
                    counts.put(u, Integer.valueOf(n));
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        final String[] items = new String[spaces.size() + 1];
                        for (int i = 0; i < spaces.size(); i++) {
                            int u = spaces.get(i).intValue();
                            Integer n = counts.get(Integer.valueOf(u));
                            items[i] = spaceLabel(u) + " · "
                                    + (n == null ? 0 : n.intValue()) + " 个分身";
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
                    exitMulti();
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
                    String out = Shell.exec(CLI + " create '" + CloneStore.cloneName(name) + "'");
                    Matcher m = Pattern.compile("\\{(\\d+):").matcher(out);
                    int got = -1;
                    if (m.find()) got = Integer.parseInt(m.group(1));
                    if (got > 0) {
                        CloneStore.markOurs(got);
                        Launcher.hideSelfFrom(got);
                        t.add(Integer.valueOf(got));
                        if (CloneStore.pruneSys(MainActivity.this)) CloneStore.pruneSpace(MainActivity.this, got, true);
                    } else {
                        newErr = out.contains("Cannot add more")
                                ? getString(R.string.space_limit_one)
                                : getString(R.string.create_failed, out.trim());
                    }
                }
                int ok = 0, skip = 0, fail = 0;
                for (String pkg : pkgs) {
                    for (int uid : t) {
                        Set<String> kept = CloneStore.keptPkgs(MainActivity.this, uid);
                        if (kept != null && kept.contains(pkg)) {
                            skip++;
                            continue;
                        }
                        String ir = Launcher.installExisting(uid, pkg);
                        String low = ir.toLowerCase();
                        boolean good = low.contains("installed") || low.contains("success");
                        if (good) {
                            CloneStore.markKept(MainActivity.this, uid, pkg);
                            ok++;
                        } else {
                            fail++;
                        }
                    }
                }
                CloneStore.dirty = true;
                final int fok = ok, fskip = skip, ffail = fail;
                final String fnewErr = newErr;
                runOnUiThread(new Runnable() {
                    public void run() {
                        multiBar.setEnabled(true);
                        String msg = getString(R.string.batch_result, fok, fskip, ffail);
                        if (fnewErr != null) msg += "\n" + fnewErr;
                        say(msg);
                        toast(msg);
                        exitMulti();
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 在应用图标右下角画一个彩色小方块，里面写空间序号（对应「空间 N」）当分身标记 */
    private Bitmap badge(Drawable base, int uid) {
        int size = dp(44);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        base.setBounds(0, 0, size, size);
        base.draw(c);
        int bw = size / 3;
        int no = CloneStore.spaceIndex(uid);        // 空间序号（只用于取色，同空间同色）；foreign 为 0
        String txt = String.valueOf(uid);           // 显示系统 uid，和其他应用（LSP/数据备份）对得上
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(BADGE[(no > 0 ? no - 1 : uid) % BADGE.length]);
        float r = bw / 5f;
        c.drawRoundRect(size - bw, size - bw, size, size, r, r, p);
        // 序号居中画在方块里；字号按位数自适应，两位数也不会溢出
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(txt.length() > 1 ? bw * 0.52f : bw * 0.66f);
        Paint.FontMetrics fm = p.getFontMetrics();
        float cy = size - bw / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(txt, size - bw / 2f, cy, p);
        return bm;
    }

    /**
     * 主身份图标：和 badge() 同一画法（画满 44dp 位图）但**不画角标**。
     * 直接 setImageDrawable 的话走 fitCenter 按原始 intrinsic 缩放，和 badge 的
     * 强制画满不是一条路径 —— 同一个图标主身份显示的会比分身小一圈（esp 实测：
     * AnyDesk 主 63px vs 分身 99px，用户看到的「图标缩小」就是这个）。
     */
    private Bitmap plainIcon(Drawable base) {
        int size = dp(44);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        base.setBounds(0, 0, size, size);
        base.draw(c);
        return bm;
    }

    /** 统一画图标：uid==0 主应用 / 关角标的分身 → plainIcon（画满 44dp，不缩小）；分身开角标 → badge */

    /** 异步懒加载图标（启动器式）：先查「最终位图」缓存直接画，没才后台 loadIcon + 合成，首屏不阻塞 */
    private void setIconAsync(final ImageView iv, final String pkg, final int uid) {
        final boolean badge = uid > 0 && sp().getBoolean("space_badge", true);
        final String key = pkg + "|" + (badge ? uid : 0);
        Bitmap bm = iconBitmapCache.get(key);
        if (bm != null) { iv.setImageBitmap(bm); return; }
        Drawable d = iconCache.get(pkg);
        if (d != null) {
            bm = badge ? badge(d, uid) : plainIcon(d);
            iconBitmapCache.put(key, bm);
            iv.setImageBitmap(bm);
            return;
        }
        iv.setImageDrawable(null);
        // 不去重：每个 ImageView 都提交任务，任务内部先查缓存——否则刷新重建后，
        // 新 ImageView 会被「正在加载」去重挡掉，回调又更新的是旧 ImageView，图标就丢了
        iconLoader.execute(new Runnable() {
            public void run() {
                Drawable dd = iconCache.get(pkg);   // 可能已被前面的任务填了
                if (dd == null) {
                    dd = appIconDrawable(MainActivity.this, pkg);
                    if (dd != null) iconCache.put(pkg, dd);
                }
                if (dd == null) return;
                Bitmap bb = iconBitmapCache.get(key);
                if (bb == null) {
                    bb = badge ? badge(dd, uid) : plainIcon(dd);
                    iconBitmapCache.put(key, bb);
                }
                final Bitmap fb = bb;
                runOnUiThread(new Runnable() {
                    public void run() { iv.setImageBitmap(fb); }
                });
            }
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ---------------- 动作 ----------------

    /**
     * 给这个应用建分身。
     *
     * 两条路：先新建一个 clone profile（多数 ROM 允许 3~5 个）；
     * 建不出来（有的 ROM perParent=1，已经占满）就复用已有的分身空间，
     * 把应用装进去 —— 一个空间装多个应用，这是 AOSP 允许的做法。
     */
    private interface NameCb { void onName(String name); }

    private void createClone(final Group g) {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say(getString(R.string.creating_clone, g.label), true);
        new Thread(new Runnable() {
            public void run() {
                // 先把应用塞进已有空间里还没装它的那个（红米/LOS 只允许 1 个空间，只能这样）
                int spare = CloneStore.pickReusableSpace(MainActivity.this, g.pkg);
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
                // 新建空间前让用户起名：名字进系统用户名，第三方可见、可区分
                runOnUiThread(new Runnable() {
                    public void run() {
                        askSpaceName(new NameCb() {
                            public void onName(final String name) {
                                createSpaceWith(g, name);
                            }
                        }, null);
                    }
                });
            }
        }).start();
    }

    /** 建一个新空间，然后把 g 装进去 */
    private void createSpaceWith(final Group g, final String name) {
        new Thread(new Runnable() {
            public void run() {
                String out = Shell.exec(CLI + " create '" + CloneStore.cloneName(name) + "'");
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

    /**
     * 长按「创建分身」：自己选装到哪个空间。
     * 点＝自动（最老的、还没装它的空间），长按＝手选；这样常用路径不用每次点弹窗。
     */
    private void pickSpaceFor(final Group g) {
        if (!Shell.ready()) {
            say(getString(R.string.need_perm));
            return;
        }
        say("正在读取分身空间…");
        new Thread(new Runnable() {
            public void run() {
                final List<Integer> spaces = CloneStore.listSpaceUids();
                runOnUiThread(new Runnable() {
                    public void run() {
                        // 多选：一次勾几个空间一起建；已经有这个应用的空间照旧提示、跳过
                        final String[] items = new String[spaces.size()];
                        final boolean[] has = new boolean[spaces.size()];
                        for (int i = 0; i < spaces.size(); i++) {
                            int u = spaces.get(i).intValue();
                            java.util.Set<String> kept = CloneStore.keptPkgs(MainActivity.this, u);
                            has[i] = kept != null && kept.contains(g.pkg);
                            items[i] = spaceLabel(u) + " · "
                                    + getString(has[i] ? R.string.space_has_app : R.string.space_free);
                        }
                        final boolean[] checked = new boolean[spaces.size()];
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(getString(R.string.pick_space_for_title, g.label))
                                .setMultiChoiceItems(items, checked,
                                        new DialogInterface.OnMultiChoiceClickListener() {
                                            public void onClick(DialogInterface d,
                                                    int which, boolean isChecked) {
                                                checked[which] = isChecked;
                                            }
                                        })
                                .setPositiveButton(android.R.string.ok,
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int w) {
                                                List<Integer> targets = new ArrayList<Integer>();
                                                boolean skipped = false;
                                                for (int i = 0; i < checked.length; i++) {
                                                    if (!checked[i]) continue;
                                                    if (has[i]) {
                                                        skipped = true;
                                                        continue;
                                                    }
                                                    targets.add(spaces.get(i));
                                                }
                                                if (targets.isEmpty()) {
                                                    toast(skipped
                                                            ? getString(R.string.space_has_app_hint,
                                                                    g.label)
                                                            : getString(R.string.batch_none_selected));
                                                    return;
                                                }
                                                if (skipped) {
                                                    toast(getString(R.string.batch_skip_has_app,
                                                            g.label));
                                                }
                                                installIntoClones(g, targets);
                                            }
                                        })
                                .setNeutralButton(R.string.new_space_option,
                                        new DialogInterface.OnClickListener() {
                                            public void onClick(DialogInterface d, int w) {
                                                askSpaceName(new NameCb() {
                                                    public void onName(String name) {
                                                        createSpaceWith(g, name);
                                                    }
                                                }, null);
                                            }
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 弹框给新空间起名；确定后回调名字（可能为空，空则系统名回落 space）；取消回调 onCancel（可空） */
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

    /** 一次装进多个已有空间：串行执行，避免同时起一堆 pm 命令 */
    private void installIntoClones(final Group g, final List<Integer> uids) {
        new Thread(new Runnable() {
            public void run() {
                for (Integer u : uids) {
                    installIntoClone(g, u.intValue(), true, "");
                }
                final int n = uids.size();
                runOnUiThread(new Runnable() {
                    public void run() {
                        say(getString(R.string.batch_create_done, n));
                    }
                });
            }
        }).start();
    }

    /** 拿到空间 uid 后：登记归属 → 装应用 → 精简 → 反馈 */
    private void installIntoClone(final Group g, final int uid, final boolean reused,
            final String name) {
        CloneStore.markOurs(uid);          // 归属登记：以后靠它 + 名字前缀认自己人
        // 不主动 start：建号时系统已经把档案拉起来了，这里再 start 只会让桌面
        // 把「刚灌进来还没删的那批应用」bind 一遍
        Launcher.hideSelfFrom(uid);
        final String ir = Launcher.installExisting(uid, g.pkg);
        // ⚠pm install-existing 成功时输出 "Package X installed for user: N"，没有 success 字样。
        // 以前只认 success ⇒ 每次创建都被当成失败：不登记、不清理、界面还报「没装进分身」
        final String low = ir.toLowerCase();
        final boolean installed = low.contains("installed") || low.contains("success");
        // 登记：这个空间里「该有」的包。清理只认这张表，不再靠「三方还是系统」猜
        if (installed) CloneStore.markKept(MainActivity.this, uid, g.pkg);
        // 新空间：装上主空间输入法并抄一份设置。
        // 老空间再装：只有这次装的正好是输入法时才同步——否则包进去了，但
        // enabled_input_methods 里没有它，键盘列表里根本选不到（看着就是「没装上」）。
        if (!reused || CloneStore.isIme(g.pkg)) CloneStore.ensureIme(uid);
        // 新空间会被系统灌进主身份里的全部应用（OP12 上 25 个三方全来了、红米 184 个包），
        // 一加桌面会把它们整个摆出来 ⇒ 建完立刻按登记表清掉，只留刚装进去的这个
        int left = 0;
        if (!reused && installed && CloneStore.pruneSys(MainActivity.this)) {
            left = CloneStore.pruneSpace(MainActivity.this, uid, true);
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
                            g.icon, CloneStore.nameOf(MainActivity.this, fu));
                }
            }
        });
    }



    /** 现有分身空间个数（CLONE 类型的用户） */
    private int countSpaces() {
        return CloneStore.listSpaceUids().size();
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
            recordRecent(0, g.pkg);   // 主应用也进「最近打开」，用 uid=0 标记
            onRecentChanged();
        } catch (Throwable t) {
            toast(getString(R.string.launch_failed, g.label));
        }
    }

    /** 分身行长按菜单：禁用的可一键启用、启用的可禁用/隐藏，也能直接移除 */
    private void cloneMenu(final Clone c, final Group g) {
        final java.util.List<String> items = new java.util.ArrayList<String>();
        final java.util.List<Integer> acts = new java.util.ArrayList<Integer>();
        boolean ro = c.foreign && !sp().getBoolean("manage_foreign", false);
        if (!ro) {
            if (c.off) { items.add(getString(R.string.menu_enable)); acts.add(1); }
            else { items.add(getString(R.string.menu_disable)); acts.add(4); }
            // 隐藏是独立的显示层标记，启用的、禁用的都能用
            items.add(getString(R.string.menu_hide)); acts.add(5);
        }
        items.add(getString(R.string.menu_open)); acts.add(2);
        items.add(getString(R.string.menu_app_info)); acts.add(8);
        // 需要 root 的入口按 Root 开关出现；只用 Shizuku 时这里什么都没有
        if (Root.on(this, Root.CLONE)) { items.add(getString(R.string.menu_copy_data)); acts.add(6); }
        if (Root.on(this, Root.BACKUP)) { items.add(getString(R.string.menu_backup_data)); acts.add(7); }
        if (!ro) { items.add(getString(R.string.menu_remove)); acts.add(3); }
        new android.app.AlertDialog.Builder(this)
                .setTitle(g.label + suffixOf(c.uid, g.pkg, 0))
                .setItems(items.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int which) {
                                int a = acts.get(which).intValue();
                                if (a == 1) enableClone(c, g);
                                else if (a == 4) disableClone(c, g);
                                else if (a == 5) hideClone(c, g);
                                else if (a == 2) launch(c, g);
                                else if (a == 8) openAppInfo(g.pkg, c.uid);
                                else if (a == 6) pickCopyTarget(g, c.uid);
                                else if (a == 7) backupData(g, c.uid);
                                else confirmRemove(c, g);
                            }
                        })
                .show();
    }

    /**
     * 主应用行长按菜单：形态和分身行一致，第一个动作是多选批量创建。
     * 只提供「对分身做点什么」，绝不放卸载/清数据这类会动主空间真实数据的项。
     */
    private void baseMenu(final Group g) {
        final java.util.List<String> items = new java.util.ArrayList<String>();
        final java.util.List<Integer> acts = new java.util.ArrayList<Integer>();
        items.add(getString(R.string.menu_batch_create)); acts.add(1);
        items.add(getString(R.string.menu_create_in)); acts.add(2);
        items.add(getString(R.string.menu_app_info)); acts.add(3);
        if (Root.on(this, Root.BACKUP)) { items.add(getString(R.string.menu_backup_data)); acts.add(6); }
        int cnt = 0;
        for (Clone c : g.clones) {
            if (c.foreign && !sp().getBoolean("manage_foreign", false)) continue;
            cnt++;
        }
        if (cnt > 0) {
            if (Root.on(this, Root.CLONE)) {
                items.add(getString(R.string.menu_copy_data)); acts.add(5);
            }
            items.add(getString(R.string.menu_remove_all, cnt)); acts.add(4);
        }
        new AlertDialog.Builder(this)
                .setTitle(g.label)
                .setItems(items.toArray(new String[0]),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                int a = acts.get(which).intValue();
                                if (a == 1) {
                                    if (gridMode()) {   // 多选只在列表视图里做，记住原模式好退回来
                                        restoreGrid = true;
                                        setGridMode(false);
                                    }
                                    multi = true;
                                    selected.clear();
                                    selected.add(g.pkg);
                                    multiBar.setVisibility(View.VISIBLE);
                                    render();
                                } else if (a == 2) {
                                    pickSpaceFor(g);
                                } else if (a == 3) {
                                    openAppInfo(g.pkg, 0);
                                } else if (a == 5) {
                                    pickCopyTarget(g, 0);
                                } else if (a == 6) {
                                    backupData(g, 0);
                                } else {
                                    confirmRemoveAll(g);
                                }
                            }
                        })
                .show();
    }

    /**
     * 跳到系统的应用详情页（只读，不改任何东西）。
     * 分身里的应用要走 --user：应用详情页是「某空间里的那个包」，不带用户永远落到主空间。
     */
    private void openAppInfo(String pkg, int user) {
        try {
            if (user == 0) {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(android.net.Uri.parse("package:" + pkg));
                startActivity(i);
                return;
            }
            if (!Shell.ready()) {
                toast(getString(R.string.app_info_failed));
                return;
            }
            Shell.execQuick("am start --user " + user
                    + " -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:" + pkg
                    + "' >/dev/null 2>&1");
        } catch (Throwable t) {
            toast(getString(R.string.app_info_failed));
        }
    }

    /** 把该应用在所有可管理空间里的分身一次卸掉；只卸应用，不动用户、不动主空间 */
    private void confirmRemoveAll(final Group g) {
        final java.util.List<Clone> list = new java.util.ArrayList<Clone>();
        final boolean foreign = sp().getBoolean("manage_foreign", false);
        for (Clone c : g.clones) {
            if (c.foreign && !foreign) continue;
            list.add(c);
        }
        if (list.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_all_title)
                .setMessage(getString(R.string.remove_all_msg, g.label, list.size()))
                .setPositiveButton(R.string.delete_clone_btn, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                int n = 0;
                                for (Clone c : list) {
                                    // 与单个移除同一套闸：不碰非自建空间
                                    if (!Launcher.safe(c.uid)) continue;
                                    if (!CloneStore.isOurs(null, c.uid)
                                            && !sp().getBoolean("manage_foreign", false)) continue;
                                    Launcher.uninstall(c.uid, g.pkg);
                                    CloneStore.unmarkKept(MainActivity.this, c.uid, g.pkg);
                                    unpinShortcut(MainActivity.this, c.uid, g.pkg);
                                    n++;
                                }
                                final int done = n;
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        say(getString(R.string.removed_all, done));
                                        refresh();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    /** 空间显示名：0 是主空间，其它是分身空间的名字 */
    private String spaceLabel(int uid) {
        String n = uid == 0 ? getString(R.string.space_main) : CloneStore.nameOf(this, uid);
        return n + " (" + uid + ")";
    }

    /**
     * 「克隆数据到…」：源就是长按的那一行（主空间 = 0，分身 = 该空间的用户 id），
     * 目标用其余装了该应用的空间，可以多选。
     */
    private void pickCopyTarget(final Group g, final int srcUser) {
        final java.util.List<Integer> targets = new java.util.ArrayList<Integer>();
        if (srcUser != 0) targets.add(Integer.valueOf(0));
        for (Clone c : g.clones) {
            if (c.uid != srcUser) targets.add(Integer.valueOf(c.uid));
        }
        if (targets.isEmpty()) {
            toast(getString(R.string.copy_data_no_clone, g.label));
            return;
        }
        String[] items = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            // 只报「空间名 (uid)」：应用级后缀是桌面图标用的，
            // 同一个空间里每个应用的后缀都不一样，放这里只会让人对不上号
            items[i] = spaceLabel(targets.get(i).intValue());
        }
        final boolean[] checked = new boolean[items.length];
        new AlertDialog.Builder(this)
                .setTitle(R.string.copy_data_pick_title)
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        java.util.List<Integer> sel = new java.util.ArrayList<Integer>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) sel.add(targets.get(i));
                        }
                        if (sel.isEmpty()) {
                            toast(getString(R.string.copy_data_pick_empty));
                            return;
                        }
                        confirmCopyData(g, srcUser, sel);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 长按菜单的「备份数据」：只给这一个空间做备份，不涉及另一边 */
    private void backupData(final Group g, final int user) {
        if (!Root.on(this, Root.BACKUP)) {
            toast(getString(R.string.root_need));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_now_title)
                .setMessage(getString(R.string.backup_now_msg) + "\n\n"
                        + spaceLabel(user) + " · " + g.label)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { runBackup(g, user); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runBackup(final Group g, final int user) {
        // 和批量备份走同一条路：过程页看得到进度/速度，失败了能在「数据备份 → 日志」里查原因
        android.content.Intent it = new android.content.Intent(this, BatchRunActivity.class);
        it.putExtra(BatchRunActivity.EXTRA_MODE, BatchRunActivity.MODE_BACKUP);
        it.putExtra(BatchRunActivity.EXTRA_USER, user);
        it.putExtra(BatchRunActivity.EXTRA_PKGS, new String[]{g.pkg});
        startActivity(it);
    }

    private void confirmCopyData(final Group g, final int srcUser,
                                 final java.util.List<Integer> dstUsers) {
        // 数据在 /data/user 下，Shizuku 的 shell 身份读写不了，必须实打实拿到 root
        if (!Shell.checkRoot()) {
            toast(getString(R.string.copy_data_no_root));
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Integer u : dstUsers) {
            if (names.length() > 0) names.append("\u3001");
            names.append(spaceLabel(u.intValue()));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.copy_data_title)
                .setMessage(getString(R.string.copy_data_msg,
                        spaceLabel(srcUser), g.label, names.toString()))
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        runCopyData(g, srcUser, dstUsers);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runCopyData(final Group g, final int srcUser,
                             final java.util.List<Integer> dstUsers) {
        // 锁屏会驱逐 CE 密钥，复制中途锁屏就写不进去；复制期间保持屏幕常亮
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable() {
            public void run() {
                int appId;
                try {
                    appId = getPackageManager().getApplicationInfo(g.pkg, 0).uid % 100000;
                } catch (Throwable t) {
                    final String m = getString(R.string.copy_data_fail, t.toString());
                    runOnUiThread(new Runnable() {
                        public void run() {
                            getWindow().clearFlags(
                                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                            toast(m);
                        }
                    });
                    return;
                }
                int ok = 0, bad = 0, perms = 0;
                String lastBak = "", firstErr = "";
                for (int i = 0; i < dstUsers.size(); i++) {
                    final int dst = dstUsers.get(i).intValue();
                    final int no = i + 1;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            say(getString(R.string.copy_data_working_multi, g.label,
                                    spaceLabel(dst), Integer.valueOf(no),
                                    Integer.valueOf(dstUsers.size())));
                        }
                    });
                    String algo = sp().getString("backup_algo", "zst");
                    int lv = sp().getInt("backup_level_" + algo, AppData.defaultLevel(algo));
                    String r = AppData.copy(srcUser, dst, g.pkg, appId, algo, lv);
                    if (r.startsWith("OK")) {
                        ok++;
                        lastBak = r.length() > 3 ? r.substring(3).trim() : "";
                        // 数据到位后再补授权，用户打开目标应用就不用再点一遍
                        perms += AppData.syncPermissions(srcUser, dst, g.pkg);
                    } else {
                        bad++;
                        if (firstErr.length() == 0) firstErr = r;
                    }
                }
                final int fok = ok, fbad = bad, fp = perms;
                final String fbak = lastBak, ferr = firstErr;
                runOnUiThread(new Runnable() {
                    public void run() {
                        getWindow().clearFlags(
                                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (fok == 1 && fbad == 0) {
                            say(getString(R.string.copy_data_ok, fbak));
                        } else if (fok > 0) {
                            say(getString(R.string.copy_data_done_multi,
                                    Integer.valueOf(fok), Integer.valueOf(fbad)));
                        } else if (ferr.startsWith("ERR_LOCKED")) {
                            toast(getString(R.string.copy_data_locked));
                        } else {
                            toast(getString(R.string.copy_data_fail, ferr));
                        }
                        if (fp > 0) toast(getString(R.string.copy_data_perms, Integer.valueOf(fp)));
                    }
                });
            }
        }).start();
    }

    /** 隐藏一个启用中的分身：只从列表里藏起来（应用保持启用、能跑），设置里可恢复 */
    private void hideClone(final Clone c, final Group g) {
        CloneStore.hideClone(this, c.uid, g.pkg);
        toast(getString(R.string.clone_hidden_ok, g.label));
        CloneStore.dirty = true;
        refresh();
    }

    /** 把被隐藏的分身重新启用（pm enable，数据原样还在） */
    private void enableClone(final Clone c, final Group g) {
        toast(getString(R.string.clone_enabling, g.label));
        new Thread(new Runnable() {
            public void run() {
                final String r = Launcher.enable(c.uid, g.pkg);
                runOnUiThread(new Runnable() {
                    public void run() {
                        boolean ok = r.toLowerCase().contains("new state");
                        toast(ok ? getString(R.string.clone_enabled, g.label) : r.trim());
                        CloneStore.dirty = true;
                        refresh();
                    }
                });
            }
        }).start();
    }

    /** 在分身里禁用（隐藏）一个应用：pm disable-user，图标消失、跑不起来，但不删数据，可一键启用 */
    private void disableClone(final Clone c, final Group g) {
        new Thread(new Runnable() {
            public void run() {
                final String r = Launcher.disable(c.uid, g.pkg);
                runOnUiThread(new Runnable() {
                    public void run() {
                        boolean ok = r.toLowerCase().contains("new state");
                        toast(ok ? getString(R.string.clone_disabled, g.label) : r.trim());
                        CloneStore.dirty = true;
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void launch(final Clone c, final Group g) {
        toast(getString(R.string.opening, c.uid, g.label));
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
        recentHeader.addView(recentTitle);
        recentHeader.addView(recentRow);
        // 不再加分隔线：下面紧跟的卡片/网格自带视觉分隔，多一条线反而重（川提）
    }

    /** 把「最近打开」区挂到指定 recent_host（无 padding）：先摘掉旧父容器，避免切换列表/网格时崩溃 */
    private void mountRecentHeader(LinearLayout parent) {
        android.view.ViewParent p = recentHeader.getParent();
        if (p instanceof ViewGroup && p != parent) {
            ((ViewGroup) p).removeView(recentHeader);
        }
        if (recentHeader.getParent() == null) {
            parent.addView(recentHeader);
        }
    }

    /** 把 tab 行挂到当前视图的 outer 顶部（recent_host 之后、列表之前），随滑动滚走/回顶 */
    private void mountTabRow(LinearLayout outer) {
        android.view.ViewParent p = tabRow.getParent();
        if (p instanceof ViewGroup && p != outer) {
            ((ViewGroup) p).removeView(tabRow);
        }
        if (tabRow.getParent() == null) {
            outer.addView(tabRow, 1);
        }
    }

    /** 刷「最近打开」区内容；返回是否有可显示的项（挂到哪由 render/buildGrid 决定） */
    private boolean refreshRecentHeader() {
        if (recentHeader == null) return false;
        recentRow.removeAllViews();
        boolean dark = isNight();
        int nameColor = dark ? 0xFFEDEDED : 0xFF1B1B1F;
        recentTitle.setTextColor(dark ? 0xFF9AA0A6 : 0xFF5F6368);

        // 收集仍存在的条目：uid=0 是打开过的主应用，>0 反查 allClones 的分身
        List<Integer> uids = new ArrayList<Integer>();
        List<String> pkgs = new ArrayList<String>();
        for (String entry : loadRecent()) {
            int sep = entry.indexOf(':');
            final int uid;
            final String pkg;
            try {
                uid = Integer.parseInt(entry.substring(0, sep));
                pkg = entry.substring(sep + 1);
            } catch (Throwable t) { continue; }
            if (uid == 0) {
                uids.add(Integer.valueOf(0));
                pkgs.add(pkg);
            } else {
                if (CloneStore.isHidden(uid, pkg)) continue;   // 隐藏的分身不进最近打开
                Clone c = allClones.get(uid + ":" + pkg);
                if (c == null) continue;   // 分身已被删 / 不在本次扫描里
                uids.add(Integer.valueOf(uid));
                pkgs.add(pkg);
            }
        }
        if (uids.isEmpty()) return false;

        // 行数上限：只画最近 cols*rows 个，超出的丢掉
        int cols = Math.max(1, recentCols());
        int max = cols * recentRows();
        while (uids.size() > max) {
            uids.remove(uids.size() - 1);
            pkgs.remove(pkgs.size() - 1);
        }

        // 「最近打开」独立于主视图：挂无 padding 的 recent_host，缩进自己管（左右 8dp 和网格一致）。
        // 格子宽 = (屏幕宽 - 16dp) / 列数 = 网格 cellW，两种视图一致、跟随列数时上下对齐。
        int avail = getResources().getDisplayMetrics().widthPixels - dp(8) * 2;
        int itemW = avail > 0 ? avail / cols : dp(64);
        int itemPadX = dp(4);
        int iconSz = dp(44);
        recentTitle.setPadding(dp(8), dp(8), dp(8), 0);
        recentRow.setPadding(dp(8), dp(4), dp(8), dp(6));

        LinearLayout line = null;
        for (int i = 0; i < uids.size(); i++) {
            if (i % cols == 0) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                recentRow.addView(line);
            }
            final int uid = uids.get(i).intValue();
            final String pkg = pkgs.get(i);
            final Clone c = uid == 0 ? null : allClones.get(uid + ":" + pkg);
            final boolean off = c != null && c.off;
            String label = labelCache.get(pkg);
            if (label == null) label = pkg;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(itemW,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            item.setPadding(itemPadX, dp(4), itemPadX, 0);
            ImageView iv = new ImageView(this);
            setIconAsync(iv, pkg, uid);
            iv.setLayoutParams(new LinearLayout.LayoutParams(iconSz, iconSz));
            if (off) iv.setAlpha(0.4f);
            item.addView(iv);
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextSize(11);
            tv.setTextColor(off ? (dark ? 0xFF7A7A7A : 0xFFA0A0A0) : nameColor);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setMaxWidth(dp(64));
            item.addView(tv);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (uid == 0) {
                        Group pg = findGroup(pkg);
                        if (pg != null) openPrimary(pg);
                    } else {
                        onRecentClick(uid, pkg, c);
                    }
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    if (uid == 0) return false;   // 主应用没有分身菜单
                    Group g = findGroup(pkg);
                    if (g != null) cloneMenu(c, g);
                    return true;
                }
            });
            line.addView(item);
        }
        return true;
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
        if (!CloneStore.isOurs(null, c.uid) && !sp().getBoolean("manage_foreign", false)) {
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
                                CloneStore.unmarkKept(MainActivity.this, c.uid, g.pkg);
                                // 「空间空了」要按删完之后实际还剩什么判断。
                                // ⚠老空间没有登记表，keptPkgs 返回 null；以前把 null 当空 ⇒
                                // 删掉一个分身就弹「要不要删空间」，点下去整个空间都没了。
                                Set<String> left = CloneStore.keptPkgs(MainActivity.this, c.uid);
                                final boolean spaceEmpty;
                                if (left != null && !left.isEmpty()) {
                                    spaceEmpty = false;            // 登记表还有，肯定不算空
                                } else {
                                    spaceEmpty = CloneStore.iconsInSpace(c.uid) == 0;   // -1（判不了）时不提示
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
        if (!CloneStore.isOurs(null, uid)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.recycle_title)
                .setMessage(getString(R.string.recycle_msg))
                .setPositiveButton(R.string.recycle_btn, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() {
                            public void run() {
                                final String r = Shell.exec(CLI + " remove " + uid);
                                if (!CloneStore.listSpaceUids().contains(Integer.valueOf(uid))) {
                                    // 空间没了，登记表 / 归属登记一起删
                                    getSharedPreferences("clone", MODE_PRIVATE)
                                            .edit().remove("space_pkgs_" + uid).apply();
                                    CloneStore.unmarkOurs(uid);
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
            java.util.List<Integer> alive = CloneStore.listSpaceUids();
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
            // 图标：优先把目标应用的原图标资源直接交给桌面渲染 —— 与真图标同一条渲染路径，
            // 必然等大。bitmap 方案（createWithAdaptiveBitmap）在部分桌面会被当普通图标
            // 再套一层边距 ⇒ 双重留白、图标小一圈（esp 上实测复现过，v4.x 已定案过一次，
            // 后来被改丢又复发）。拿不到资源（icon=0）才退回 bitmap 兜底。
            Icon ic = appIcon(a, pkg);
            if (ic != null) {
                b.setIcon(ic);
            } else {
                // bitmap 兜底：按桌面自己报的最大尺寸画（Island 也是这么干的）：
                // 画布 = (1+2*extraInset) × 最大尺寸，内容画在中间安全区、铺白底，
                // 桌面再套 mask 不会二次留白
                Drawable src = icon != null ? icon : appIconDrawable(a, pkg);
                Bitmap bm = adaptiveIcon(src, sm.getIconMaxWidth(), sm.getIconMaxHeight());
                if (bm != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        b.setIcon(Icon.createWithAdaptiveBitmap(bm));
                    } else {
                        b.setIcon(Icon.createWithBitmap(bm));
                    }
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
        say(s, false);
    }

    /** sticky=true 时不自动回落：创建空间这种长操作要用，否则 4 秒后提示消失、用户以为卡住 */
    private void say(String s, boolean sticky) {
        if (status == null) return;
        status.setText(s);
        ui.removeCallbacks(idle);
        if (!sticky) ui.postDelayed(idle, 4000);
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
